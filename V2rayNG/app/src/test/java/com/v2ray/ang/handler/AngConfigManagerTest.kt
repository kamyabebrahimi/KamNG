package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for AngConfigManager.applySubscriptionOverrides.
 */
class AngConfigManagerTest {

    private fun createProfile(): ProfileItem =
        ProfileItem.create(EConfigType.VLESS).apply {
            server = "188.114.97.3"
            serverPort = "443"
        }

    @Test
    fun test_applySubscriptionOverrides_replacesAddressAndPort() {
        val profile = createProfile()

        AngConfigManager.applySubscriptionOverrides(
            profile,
            SubscriptionItem(overrideAddress = "example.com", overridePort = 8443)
        )

        assertEquals("example.com", profile.server)
        assertEquals("8443", profile.serverPort)
    }

    @Test
    fun test_applySubscriptionOverrides_replacesOnlyTheConfiguredValue() {
        val addressOnly = createProfile()
        AngConfigManager.applySubscriptionOverrides(addressOnly, SubscriptionItem(overrideAddress = "example.com"))
        assertEquals("example.com", addressOnly.server)
        assertEquals("443", addressOnly.serverPort)

        val portOnly = createProfile()
        AngConfigManager.applySubscriptionOverrides(portOnly, SubscriptionItem(overridePort = 8443))
        assertEquals("188.114.97.3", portOnly.server)
        assertEquals("8443", portOnly.serverPort)
    }

    @Test
    fun test_applySubscriptionOverrides_keepsProfileWhenNothingIsConfigured() {
        val subItems = listOf(
            null,
            SubscriptionItem(),
            SubscriptionItem(overrideAddress = "   ", overridePort = 0),
            SubscriptionItem(overridePort = 70000),
        )

        for (subItem in subItems) {
            val profile = createProfile()

            AngConfigManager.applySubscriptionOverrides(profile, subItem)

            assertEquals("188.114.97.3", profile.server)
            assertEquals("443", profile.serverPort)
        }
    }

    @Test
    fun test_applySubscriptionOverrides_trimsAddress() {
        val profile = createProfile()

        AngConfigManager.applySubscriptionOverrides(profile, SubscriptionItem(overrideAddress = " example.com "))

        assertEquals("example.com", profile.server)
    }

    @Test
    fun rawTunnelImportSelectsNativeAmneziaOnlyWhenItsSettingsArePresent() {
        val key = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val wg = "[Interface]\nPrivateKey=$key\nAddress=10.0.0.2/32\n[Peer]\nPublicKey=$key\nEndpoint=example.test:51820\nAllowedIPs=0.0.0.0/0\n"
        val awg = wg.replace("[Peer]", "Jc=7\nH1=123\n[Peer]")
        assertEquals(EConfigType.WIREGUARD, AngConfigManager.parseTunnelConfiguration(wg).configType)
        val native = AngConfigManager.parseTunnelConfiguration(awg)
        assertEquals("amneziawg", native.nativeEngine)
        assertEquals(awg, native.nativeEngineConfig)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            AngConfigManager.parseTunnelConfiguration(awg.replace(key, "invalid"))
        }
    }

    @Test
    fun automaticNativePortsAvoidExistingProfilesAndReportExhaustion() {
        assertEquals(18001, com.v2ray.ang.core.NativeEngineConfig.availableAmneziaPort(emptySet()))
        assertEquals(18003, com.v2ray.ang.core.NativeEngineConfig.availableAmneziaPort(setOf(18000, 18001, 18002)))
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            com.v2ray.ang.core.NativeEngineConfig.availableAmneziaPort((18001..65535).toSet())
        }
    }
}
