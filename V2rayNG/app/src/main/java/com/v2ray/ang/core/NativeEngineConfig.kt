package com.v2ray.ang.core

import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.util.JsonUtil

/** Native profile data is persisted with the SOCKS profile; Xray remains the routing owner. */
data class NativeEngineConfig(
    val engine: String,
    val port: Int,
    val configuration: String,
    val resolvers: String = "",
) {
    fun validate() {
        require(engine in setOf("amneziawg", "cottendns")) { "Unsupported native engine" }
        require(port in 1024..65535) { "Invalid native listener port" }
        require(configuration.isNotBlank() && configuration.length <= 262144) { "Invalid native configuration" }
        if (engine == "amneziawg") {
            require(Regex("(?im)^\\s*\\[Interface]\\s*(?:#.*)?$").containsMatchIn(configuration) && Regex("(?im)^\\s*\\[Peer]\\s*(?:#.*)?$").containsMatchIn(configuration)) { "AmneziaWG requires Interface and Peer sections" }
            require(!Regex("(?im)^\\s*(PreUp|PostUp|PreDown|PostDown)\\s*=").containsMatchIn(configuration)) { "Shell hooks are unsupported" }
        } else {
            require(Regex("(?m)^\\s*DOMAINS\\s*=").containsMatchIn(configuration)) { "CottenDNS requires DOMAINS" }
            require(Regex("(?m)^\\s*ENCRYPTION_KEY\\s*=").containsMatchIn(configuration)) { "CottenDNS requires ENCRYPTION_KEY" }
            require(resolvers.isNotBlank() && resolvers.length <= 262144) { "CottenDNS requires a resolver list" }
        }
    }

    /** Preserve advanced tunnel settings while taking ownership of local listeners and mobile startup. */
    fun renderedConfiguration(): String {
        if (engine != "cottendns") return configuration
        val enforced = linkedMapOf(
            "PROTOCOL_TYPE" to "\"SOCKS5\"",
            "LISTEN_IP" to "\"127.0.0.1\"",
            "LISTEN_PORT" to port.toString(),
            "SOCKS5_AUTH" to "false",
            "LOCAL_DNS_ENABLED" to "false",
            "RESOLVER_IP_MODE" to "\"auto\"",
            "STARTUP_MODE" to "\"resolvers\"",
            "TERMINAL_UI" to "\"plain\"",
            "LOG_TO_FILE" to "false",
            "LOCAL_DNS_CACHE_PERSIST_TO_FILE" to "false",
        )
        var result = configuration
        enforced.forEach { (key, value) ->
            val pattern = Regex("(?m)^\\s*" + key + "\\s*=.*$")
            result = if (pattern.containsMatchIn(result)) pattern.replace(result) { "$key = $value" } else result + "\n$key = $value\n"
        }
        return result
    }

    companion object {
        const val EXTENSION = "kamngNativeCores"

        fun availableAmneziaPort(occupied: Set<Int>): Int =
            (18001..65535).firstOrNull { it !in occupied } ?: error("No native listener port available")

        fun attach(content: String, cores: List<NativeEngineConfig>): String {
            if (cores.isEmpty()) return content
            val json = com.google.gson.JsonParser.parseString(content).asJsonObject
            json.add(EXTENSION, com.google.gson.JsonParser.parseString(JsonUtil.toJson(cores)))
            return json.toString()
        }

        fun extract(content: String): List<NativeEngineConfig> {
            val json = com.google.gson.JsonParser.parseString(content).asJsonObject
            return json.getAsJsonArray(EXTENSION)?.map {
                com.google.gson.Gson().fromJson(it, NativeEngineConfig::class.java).also { core -> core.validate() }
            }.orEmpty().also { cores ->
                require(cores.map { it.port }.distinct().size == cores.size) { "Native listener ports conflict" }
            }
        }

        fun runtimeContent(content: String): String {
            val json = com.google.gson.JsonParser.parseString(content).asJsonObject
            json.remove(EXTENSION)
            return json.toString()
        }

        /** Probe ports are separate from a running session and from simultaneous probes. */
        fun remap(content: String, ports: Map<Int, Int>): String {
            val json = com.google.gson.JsonParser.parseString(runtimeContent(content)).asJsonObject
            json.getAsJsonArray("outbounds")?.forEach {
                val outbound = it.asJsonObject
                if (outbound.get("protocol")?.asString == "socks") {
                    val settings = outbound.getAsJsonObject("settings")
                    if (settings?.get("address")?.asString == "127.0.0.1") {
                        ports[settings.get("port")?.asInt]?.let { port -> settings.addProperty("port", port) }
                    }
                    settings?.getAsJsonArray("servers")?.forEach { server ->
                        val item = server.asJsonObject
                        if (item.get("address")?.asString == "127.0.0.1") {
                            ports[item.get("port")?.asInt]?.let { port -> item.addProperty("port", port) }
                        }
                    }
                }
            }
            return json.toString()
        }

        fun of(profile: ProfileItem): NativeEngineConfig? {
            val engine = profile.nativeEngine?.takeIf { it.isNotBlank() } ?: return null
            require(profile.configType == EConfigType.SOCKS) { "Native engine requires a SOCKS profile" }
            return NativeEngineConfig(engine, profile.serverPort?.toIntOrNull() ?: 0, profile.nativeEngineConfig.orEmpty(), profile.nativeEngineResolvers.orEmpty()).also { it.validate() }
        }

        fun resolve(outbounds: List<CoreConfigContext.ResolvedOutbound>): List<NativeEngineConfig> {
            outbounds.filter { it.resolvedType == CoreResolvedType.PROXYCHAIN }.forEach { outbound ->
                require(outbound.resolvedProfiles.dropLast(1).none { !it.nativeEngine.isNullOrBlank() }) {
                    "A native engine must be the final transport in a proxy chain"
                }
            }
            return outbounds.flatMap { it.resolvedProfiles }.mapNotNull(::of).distinct().also { cores ->
                require(cores.map { it.port }.distinct().size == cores.size) { "Native listener ports conflict" }
            }
        }
    }
}
