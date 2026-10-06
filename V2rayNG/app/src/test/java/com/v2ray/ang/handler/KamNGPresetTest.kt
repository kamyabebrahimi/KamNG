package com.v2ray.ang.handler

import com.google.gson.Gson
import com.v2ray.ang.dto.entities.RulesetItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class KamNGPresetTest {
    private fun rules(): List<RulesetItem> = Gson().fromJson(
        File("src/main/assets/custom_routing_white_iran").readText(),
        Array<RulesetItem>::class.java
    ).toList()

    @Test
    fun presetsRetainSeparateDomainAndIpMatches() {
        val rules = rules()
        assertEquals(rules.size, rules.map { it.id }.toSet().size)
        assertTrue(rules.all { it.id.isNotBlank() })
        assertTrue(rules.none { !it.domain.isNullOrEmpty() && !it.ip.isNullOrEmpty() })
        assertEquals("proxy", rules.first { it.id == "kamng-telegram-ip" }.outboundTag)
        assertTrue(rules.first { it.id == "kamng-telegram-ip" }.ip!!.any { ":" in it })
    }

    @Test
    fun aiRulesDoNotCaptureEntireSharedPlatforms() {
        val ai = rules().first { it.id == "kamng-ai" }
        assertFalse(ai.domain!!.any { it in setOf("domain:google.com", "domain:microsoft.com", "domain:x.com") })
        assertTrue("full:gemini.google.com" in ai.domain!!)
        assertTrue("full:copilot.microsoft.com" in ai.domain!!)
        assertTrue("domain:openai.com" in ai.domain!!)
    }

    @Test
    fun specificAiRulesPrecedeGoogleAndSharedLoginIsOptIn() {
        val rules = rules()
        val ids = rules.map { it.id }
        assertTrue(ids.indexOf("kamng-chatgpt") < ids.indexOf("kamng-ai"))
        assertTrue(ids.indexOf("kamng-ai") < ids.indexOf("kamng-google"))
        assertFalse(rules.first { it.id == "kamng-ai-support" }.enabled)
    }

    @Test
    fun existingLocalAndIranBypassesAreRetained() {
        val rules = rules()
        assertTrue(rules.any { it.outboundTag == "direct" && it.ip?.contains("geoip:ir") == true })
        assertTrue(rules.any { it.outboundTag == "direct" && it.domain?.contains("domain:ir") == true })
        assertTrue(rules.any { it.outboundTag == "direct" && it.ip?.contains("fc00::/7") == true })
        assertTrue(rules.any { it.outboundTag == "direct" && it.domain?.contains("geosite:private") == true })
    }
}
