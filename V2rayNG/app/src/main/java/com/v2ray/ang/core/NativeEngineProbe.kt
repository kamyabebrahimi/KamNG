package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

object NativeEngineProbe {
    suspend fun measure(context: Context, guid: String, result: ConfigResult, test: (String) -> Long): Long =
        withTimeoutOrNull(if (result.nativeCores.any { it.engine == "cottendns" }) 200000 else 30000) {
            coroutineScope {
                val cores = result.nativeCores
                val ports = linkedMapOf<Int, Int>()
                cores.forEach { core ->
                    var port: Int
                    do { port = Utils.findRandomFreePort() } while (port in ports.values)
                    ports[core.port] = port
                }
                val content = NativeEngineConfig.remap(result.content, ports)
                val session = NativeEngineSession(context, guid, this) { }
                try {
                    runInterruptible(Dispatchers.IO) {
                        session.start(cores.map { it.copy(port = ports.getValue(it.port)) }, content)
                        test(content)
                    }
                } finally { session.close() }
            }
        } ?: -1L
}
