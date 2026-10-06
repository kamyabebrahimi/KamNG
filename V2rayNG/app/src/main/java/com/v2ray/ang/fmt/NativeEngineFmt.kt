package com.v2ray.ang.fmt
import com.v2ray.ang.core.NativeEngineConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import java.util.Base64

/** Ordinary SOCKS links cannot represent complete native-engine settings. */
object NativeEngineFmt {
    const val SCHEME = "kamng://"
    fun toUri(profile: ProfileItem): String {
        require(NativeEngineConfig.of(profile) != null)
        return SCHEME + Base64.getUrlEncoder().withoutPadding().encodeToString(JsonUtil.toJson(profile).toByteArray(Charsets.UTF_8))
    }
    fun parse(value: String): ProfileItem? = try {
        require(value.startsWith(SCHEME) && value.length <= 800000)
        val raw = String(Base64.getUrlDecoder().decode(value.removePrefix(SCHEME)), Charsets.UTF_8)
        JsonUtil.fromJsonSafe(raw, ProfileItem::class.java)?.takeIf {
            it.configType == EConfigType.SOCKS && NativeEngineConfig.of(it) != null
        }
    } catch (_: Exception) { null }

    private val interfaceHeader = Regex("(?im)^\\s*\\[Interface]\\s*(?:#.*)?$")
    private val peerHeader = Regex("(?im)^\\s*\\[Peer]\\s*(?:#.*)?$")
    private val amneziaParameters = Regex("(?im)^\\s*(Jc|Jmin|Jmax|S[1-4]|H[1-4]|I[1-5]|HeaderProtection|RekeyAfterTime|RandomTrailing|DisableCookies|header_protection_key|content_padding_addition|rekey_after_time|rekey_timeout|reject_after_time|keepalive_timeout|max_handshake_attempts|random_trailers|disable_cookies)\\s*=")
    private val amneziaComment = Regex("(?im)^\\s*[#;].*\\bAmnezia(?:WG)?\\b")

    fun isTunnelConfiguration(value: String): Boolean {
        val content = value.removePrefix("\uFEFF")
        return interfaceHeader.containsMatchIn(content) && peerHeader.containsMatchIn(content)
    }

    fun isAmneziaConfiguration(value: String): Boolean =
        isTunnelConfiguration(value) &&
            (amneziaParameters.containsMatchIn(value) || amneziaComment.containsMatchIn(value))

    /** A raw AWG configuration belongs to its native core, never the ordinary WireGuard field parser. */
    fun parseAmneziaConfiguration(value: String): ProfileItem {
        require(value.length <= 262144 && isAmneziaConfiguration(value)) { "Invalid AmneziaWG configuration" }
        val content = value.removePrefix("\uFEFF")
        val interfaceValues = linkedMapOf<String, String>()
        val peers = mutableListOf<MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        content.lineSequence().forEach { raw ->
            val line = raw.substringBefore("#").trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEach
            when {
                line.equals("[Interface]", ignoreCase = true) -> {
                    require(current == null && peers.isEmpty()) { "Invalid AmneziaWG section order" }
                    current = interfaceValues
                }
                line.equals("[Peer]", ignoreCase = true) -> {
                    require(current != null) { "Missing AmneziaWG interface" }
                    current = linkedMapOf<String, String>().also { peers.add(it) }
                }
                else -> {
                    val parts = line.split("=", limit = 2)
                    require(current != null && parts.size == 2) { "Invalid AmneziaWG setting" }
                    val key = parts[0].trim().lowercase()
                    if (key in setOf("privatekey", "publickey", "presharedkey")) {
                        require(!current!!.containsKey(key)) { "Duplicate AmneziaWG identity" }
                    }
                    current!![key] = parts[1].trim()
                }
            }
        }
        fun validKey(key: String?): Boolean = try {
            key != null && Base64.getDecoder().decode(key).size == 32
        } catch (_: IllegalArgumentException) { false }
        require(validKey(interfaceValues["privatekey"]) && !interfaceValues["address"].isNullOrBlank()) {
            "Missing or invalid AmneziaWG interface identity"
        }
        require(peers.isNotEmpty()) { "Missing AmneziaWG peer" }
        peers.forEach { peer ->
            val endpoint = peer["endpoint"].orEmpty()
            val separator = endpoint.lastIndexOf(':')
            require(validKey(peer["publickey"]) && !peer["allowedips"].isNullOrBlank()) {
                "Missing or invalid AmneziaWG peer identity"
            }
            peer["presharedkey"]?.let { require(validKey(it)) { "Invalid AmneziaWG preshared key" } }
            require(separator > 0 && (endpoint.substring(separator + 1).toIntOrNull() ?: 0) in 1..65535) {
                "Missing or invalid AmneziaWG endpoint"
            }
        }
        val name = Regex("(?im)^\\s*[#;]\\s*Name\\s*[:=]\\s*(.+)$").find(content)?.groupValues?.get(1)?.trim()
        return ProfileItem.create(EConfigType.SOCKS).apply {
            remarks = name?.takeIf { it.isNotBlank() } ?: peers.first().getValue("endpoint")
            nativeEngine = "amneziawg"
            nativeEngineConfig = content
            server = "127.0.0.1"
            serverPort = "18001"
        }.also { NativeEngineConfig.of(it) }
    }
}
