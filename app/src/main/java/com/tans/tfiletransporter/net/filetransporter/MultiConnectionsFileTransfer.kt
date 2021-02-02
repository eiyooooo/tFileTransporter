package com.tans.tfiletransporter.net.filetransporter

import android.os.Environment
import android.util.Log
import com.tans.tfiletransporter.file.FileConstants
import com.tans.tfiletransporter.net.model.FileMd5
import com.tans.tfiletransporter.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicLong
import kotlin.jvm.Throws

// 1 MB
private const val MULTI_CONNECTIONS_BUFFER_SIZE: Int = 1024 * 1024
private const val MULTI_CONNECTIONS_MAX: Int = 100
// 10 MB
private const val MULTI_CONNECTIONS_MIN_FRAME_SIZE: Long = 1024 * 1024 * 10
private const val MULTI_CONNECTIONS_FILES_TRANSFER_PORT = 6669


@Throws(IOException::class)
suspend fun startMultiConnectionsFileServer(
        fileMd5: FileMd5,
        localAddress: InetAddress,
        progress: suspend (hasSend: Long, size: Long) -> Unit = { _, _ -> }
) = coroutineScope {
    val md5 = fileMd5.md5
    val file = fileMd5.file
    val finishCheckChannel = Channel<Unit>(1)
    val path = Paths.get(FileConstants.homePathString, file.path)
    if (file.size <= 0 || !Files.exists(path) || Files.isDirectory(path)) {
        return@coroutineScope
    }
    val ssc = openAsynchronousServerSocketChannelSuspend()
    ssc.use {
        ssc.setOptionSuspend(StandardSocketOptions.SO_REUSEADDR, true)
        ssc.bindSuspend(InetSocketAddress(localAddress, MULTI_CONNECTIONS_FILES_TRANSFER_PORT), MULTI_CONNECTIONS_MAX)
        val fileData = RandomAccessFile(path.toFile(), "r")
        fileData.use {
            val progressLong = AtomicLong(0L)

            /**
             * Read Sequence:
             * 1. File's MD5 16 bytes.
             * 2. File's frame start 8 bytes.
             * 3. File's frame end 8 bytes.
             * Write:
             * File's frame.
             */
            suspend fun newClient(client: AsynchronousSocketChannel) = client.use {
                val buffer = ByteBuffer.allocate(MULTI_CONNECTIONS_BUFFER_SIZE)
                client.readSuspendSize(buffer, 16)
                val remoteMd5 = buffer.copyAvailableBytes()
                if (!md5.contentEquals(remoteMd5)) {
                    client.close()
                }
                client.readSuspendSize(buffer, 8)
                val start = buffer.asLongBuffer().get()
                client.readSuspendSize(buffer, 8)
                val end = buffer.asLongBuffer().get()
                if (start >= end || end > file.size) {
                    client.close()
                }
                val limitReadSize = end - start
                var offset: Long = start
                var hasRead: Long = 0
                val bufferSize = buffer.capacity()
                while (true) {
                    val thisTimeRead = if (bufferSize + hasRead >= limitReadSize) {
                        (limitReadSize - hasRead).toInt()
                    } else {
                        bufferSize
                    }
                    synchronized(fileData) {
                        fileData.seek(offset)
                        runBlocking { fileData.channel.readSuspendSize(byteBuffer = buffer, size = thisTimeRead) }
                    }
                    client.writeSuspendSize(buffer, buffer.copyAvailableBytes())
                    hasRead += thisTimeRead
                    offset += thisTimeRead
                    val allSend = progressLong.addAndGet(thisTimeRead.toLong())
                    progress(allSend, file.size)
                    if (allSend >= file.size) {
                        finishCheckChannel.send(Unit)
                    }
                    if (hasRead >= limitReadSize) {
                        break
                    }
                }
            }

            val job = launch(Dispatchers.IO) {
                while (true) {
                    val client = ssc.acceptSuspend()
                    launch(Dispatchers.IO) {
                        val result = kotlin.runCatching {
                            newClient(client)
                        }
                        if (result.isFailure) {
                            Log.e("startMultiConnectionsFileServer", "startMultiConnectionsFileServer", result.exceptionOrNull())
                        }
                    }
                }
            }
            finishCheckChannel.receive()
            job.cancel("File: ${file.name}, Download Finish")
        }
    }
}


suspend fun startMultiConnectionsFileClient(
        fileMd5: FileMd5,
        serverAddress: InetAddress,
        progress: suspend (hasSend: Long, size: Long) -> Unit = { _, _ -> }
) = coroutineScope {

    val file = fileMd5.file
    val md5 = fileMd5.md5
    val fileSize = file.size
    val downloadDir = Paths.get(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path, "tFileTransfer")
    if (!Files.exists(downloadDir)) {
        Files.createDirectory(downloadDir)
    }
    val path = downloadDir.newChildFile(file.name)
    val fileData = RandomAccessFile(path.toFile(), "rw")
    fileData.use {

        fileData.setLength(fileSize)
        val progressLong = AtomicLong(0L)

        /**
         * Write Sequence:
         * 1. File's MD5 16 bytes.
         * 2. File's frame start 8 bytes.
         * 3. File's frame end 8 bytes.
         * Read:
         * File's frame.
         */
        suspend fun downloadFrame(start: Long, end: Long, retry: Boolean = true) {
            val result = kotlin.runCatching {
                val sc = openAsynchronousSocketChannel()
                sc.use {
                    sc.setOptionSuspend(StandardSocketOptions.SO_REUSEADDR, true)
                    sc.connectSuspend(InetSocketAddress(serverAddress, MULTI_CONNECTIONS_FILES_TRANSFER_PORT))
                    val buffer = ByteBuffer.allocate(MULTI_CONNECTIONS_BUFFER_SIZE)
                    sc.writeSuspendSize(buffer, md5)
                    sc.writeSuspendSize(buffer, start.toBytes())
                    sc.writeSuspendSize(buffer, end.toBytes())
                    val limitReadSize = end - start
                    var offset: Long = start
                    var hasRead: Long = 0
                    val bufferSize = buffer.capacity()
                    while (true) {
                        val thisTimeRead = if (bufferSize + hasRead >= limitReadSize) {
                            (limitReadSize - hasRead).toInt()
                        } else {
                            bufferSize
                        }
                        sc.readSuspendSize(buffer, thisTimeRead)
                        synchronized(fileData) {
                            fileData.seek(offset)
                            runBlocking { fileData.channel.writeSuspendSize(buffer, buffer.copyAvailableBytes()) }
                        }
                        offset += thisTimeRead
                        hasRead += thisTimeRead
                        progress(progressLong.addAndGet(thisTimeRead.toLong()), fileSize)
                        if (hasRead >= limitReadSize) {
                            break
                        }
                    }
                }
            }
            if (result.isFailure) {
                if (retry) {
                    Log.e("startMultiConnectionsFileClient", "startMultiConnectionsFileClient", result.exceptionOrNull())
                    downloadFrame(start, end, false)
                } else {
                    throw result.exceptionOrNull()!!
                }
            }
        }

        val (frameSize: Long, frameCount: Int) = if (fileSize <= MULTI_CONNECTIONS_MIN_FRAME_SIZE * MULTI_CONNECTIONS_MAX) {
            MULTI_CONNECTIONS_MIN_FRAME_SIZE to (fileSize / MULTI_CONNECTIONS_MIN_FRAME_SIZE).toInt() + if (fileSize % MULTI_CONNECTIONS_MIN_FRAME_SIZE > 0) 1 else 0
        } else {
            val frameSize = fileSize / (MULTI_CONNECTIONS_MAX - 1)
            frameSize to (if (fileSize % frameSize > 0 ) MULTI_CONNECTIONS_MAX else MULTI_CONNECTIONS_MAX - 1)
        }
        for (i in 0 until frameCount) {
            val start = i * frameSize
            if (start >= fileSize) { break }
            val end = if (start + frameSize > fileSize) {
                fileSize - start
            } else {
                start + frameSize
            }
            launch(Dispatchers.IO) { downloadFrame(start, end) }
        }

    }
}
