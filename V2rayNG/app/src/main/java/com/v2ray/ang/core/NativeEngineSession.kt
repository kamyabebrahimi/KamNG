package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * One daemon session or probe owns its own processes and private temporary files.
 * Unlike Aether these processes dial outside Xray, through the app UID excluded from the VPN.
 */
class NativeEngineSession(
    private val context: Context,
    private val profileId: String,
    parent: CoroutineScope,
    private val onUnexpectedExit: () -> Unit,
) {
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner + Dispatchers.IO)
    private val processes = CopyOnWriteArrayList<Process>()
    @Volatile private var stopping = false

    /** Called only from the service/probe setup worker, never the main thread. */
    fun start(cores: List<NativeEngineConfig>, content: String) {
        try {
            val json = com.google.gson.JsonParser.parseString(content).asJsonObject
            val inboundPorts = json.getAsJsonArray("inbounds")?.mapNotNull {
                it.asJsonObject.get("port")?.takeIf { p -> p.isJsonPrimitive }?.asString?.toIntOrNull()
            }.orEmpty()
            require(cores.none { it.port in inboundPorts }) { "Native listener overlaps an Xray inbound" }
            cores.forEach { startOne(it) }
        } catch (e: Exception) {
            close()
            LogUtil.e(AppConfig.TAG, "Native engine setup failed; mode=shared profile=$profileId", e)
            throw IllegalStateException("Native engine could not start")
        }
    }

    private fun startOne(core: NativeEngineConfig) {
        core.validate()
        check(!stopping && owner.isActive) { "Native session stopped" }
        // Reject occupied ports before launching, rather than mistaking another listener for this engine.
        java.net.ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", core.port)) }
        val directory = File(context.filesDir, "native-session-" + UUID.randomUUID())
        check(directory.mkdir())
        directory.setReadable(false, false); directory.setWritable(false, false); directory.setExecutable(false, false)
        directory.setReadable(true, true); directory.setWritable(true, true); directory.setExecutable(true, true)
        val config = File(directory, "config")
        val resolvers = File(directory, "resolvers")
        val executable = File(context.applicationInfo.nativeLibraryDir,
            if (core.engine == "amneziawg") "libkamng_awg.so" else "libcottendns_client.so")
        var process: Process? = null
        try {
            check(executable.isFile) { "Native engine unavailable on this ABI" }
            config.writeText(core.renderedConfiguration())
            config.setReadable(false, false); config.setWritable(false, false)
            config.setReadable(true, true); config.setWritable(true, true)
            val args = if (core.engine == "amneziawg") {
                listOf(executable.absolutePath, "-config", config.absolutePath, "-listen", "127.0.0.1:" + core.port)
            } else {
                resolvers.writeText(core.resolvers)
                resolvers.setReadable(false, false); resolvers.setWritable(false, false)
                resolvers.setReadable(true, true); resolvers.setWritable(true, true)
                listOf(executable.absolutePath, "-config", config.absolutePath, "-resolvers", resolvers.absolutePath,
                    "-listen-ip", "127.0.0.1", "-listen-port", core.port.toString(), "-protocol-type", "SOCKS5",
                    "-socks5-auth=false", "-local-dns-enabled=false", "-terminal-ui", "plain",
                    "-startup-mode", "resolvers", "-log-to-file=false", "-log-dir", directory.absolutePath,
                    "-local-dns-cache-persist-to-file=false")
            }
            check(!stopping && owner.isActive) { "Native session stopped" }
            process = ProcessBuilder(args).directory(directory).redirectErrorStream(true).start()
            process.outputStream.close()
            processes.add(process)
            val owned = process
            // Native output can contain keys/configuration errors: drain it without logging raw text.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { runInterruptible { owned.inputStream.use { stream -> val buffer = ByteArray(4096); while (stream.read(buffer) >= 0) { } } } }
                catch (_: Exception) { /* Process shutdown closes the pipe. */ }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val exit = runInterruptible { owned.waitFor() }
                    if (!stopping) {
                        LogUtil.w(AppConfig.TAG, "Native engine exited; mode=shared profile=$profileId engine=" + core.engine + " exit=$exit")
                        onUnexpectedExit()
                    }
                } finally {
                    owned.destroyForcibly()
                    processes.remove(owned)
                    directory.deleteRecursively()
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(if (core.engine == "cottendns") 180 else 20)
            while (System.nanoTime() < deadline) {
                check(!stopping && owned.isAlive) { "Native process stopped during setup" }
                if (isSOCKSReady(core.port)) return
                Thread.sleep(100)
            }
            error("Native listener did not become ready")
        } catch (e: Exception) {
            process?.destroyForcibly()
            if (process == null) directory.deleteRecursively()
            throw e
        }
    }

    fun close() {
        stopping = true
        processes.forEach { it.destroyForcibly() }
        scope.cancel()
    }

    /** Reload runs on the setup worker; await release of only this session's listeners. */
    fun closeAndWait() {
        val owned = processes.toList()
        close()
        owned.forEach { it.waitFor(500, TimeUnit.MILLISECONDS) }
    }

    companion object {
        private fun isSOCKSReady(port: Int): Boolean = try {
            Socket().use {
                it.connect(InetSocketAddress("127.0.0.1", port), 200)
                it.soTimeout = 200
                it.getOutputStream().write(byteArrayOf(5, 1, 0))
                it.getInputStream().read() == 5 && it.getInputStream().read() == 0
            }
        } catch (_: Exception) { false }
    }
}
