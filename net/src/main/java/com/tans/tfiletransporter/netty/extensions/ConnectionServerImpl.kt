package com.tans.tfiletransporter.netty.extensions

import com.tans.tfiletransporter.ILog
import com.tans.tfiletransporter.netty.INettyConnectionTask

class ConnectionServerImpl(
    val connectionTask: INettyConnectionTask,
    val serverManager: IServerManager
) : INettyConnectionTask by connectionTask, IServerManager by serverManager

@Suppress("UNCHECKED_CAST")
fun <T> INettyConnectionTask.withServer(
    converterFactory: IConverterFactory = DefaultConverterFactory(),
    log: ILog
): T {
    return when (this) {
        is ConnectionClientImpl -> {
            val impl = DefaultServerManager(
                connectionTask = this.connectionTask,
                converterFactory = converterFactory
            )
            ConnectionServerClientImpl(
                connectionTask = this.connectionTask,
                serverManager = impl,
                clientManager = this.clientManager
            ) as T
        }
        is ConnectionServerImpl -> {
            val impl = DefaultServerManager(
                connectionTask = this.connectionTask,
                converterFactory = converterFactory
            )
            this.clearAllServers()
            ConnectionServerImpl(
                connectionTask = this.connectionTask,
                serverManager = impl
            ) as T
        }
        is ConnectionServerClientImpl -> {
            val impl = DefaultServerManager(
                connectionTask = this.connectionTask,
                converterFactory = converterFactory
            )
            this.clearAllServers()
            ConnectionServerClientImpl(
                connectionTask = this.connectionTask,
                serverManager = impl,
                clientManager = this.clientManager
            ) as T
        }
        else -> {
            val impl = DefaultServerManager(
                connectionTask = this,
                converterFactory = converterFactory
            )
            ConnectionServerImpl(
                connectionTask = this,
                serverManager = impl
            ) as T
        }
    }
}