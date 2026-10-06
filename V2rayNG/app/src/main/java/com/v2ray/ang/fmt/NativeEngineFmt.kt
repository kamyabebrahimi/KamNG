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
}
