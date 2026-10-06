package com.v2ray.ang.core
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeEngineConfigTest {
    private val awg = NativeEngineConfig("amneziawg", 18001, "[Interface]\nPrivateKey = key\n[Peer]\n")
    private val cotten = NativeEngineConfig("cottendns", 18000, "DOMAINS = [\"t.example\"]\nENCRYPTION_KEY = \"secret\"\nLISTEN_IP = \"0.0.0.0\"\nLISTEN_PORT = 9999\nRESOLVER_TRANSPORT = \"tcp\"\n", "1.1.1.1\n")
    @Test fun exportAndRuntimeKeepResponsibilitiesSeparate() {
        val content = """{"inbounds":[],"outbounds":[{"protocol":"socks","settings":{"address":"127.0.0.1","port":18001}}]}"""
        val exported = NativeEngineConfig.attach(content, listOf(awg))
        assertEquals(listOf(awg), NativeEngineConfig.extract(exported))
        assertFalse(NativeEngineConfig.runtimeContent(exported).contains(NativeEngineConfig.EXTENSION))
        assertTrue(NativeEngineConfig.remap(exported, mapOf(18001 to 21000)).contains("21000"))
        assertFalse(NativeEngineConfig.remap(exported, mapOf(18001 to 21000)).contains("18001"))
    }
    @Test fun legacyJsonNeedsNoEngines() { assertTrue(NativeEngineConfig.extract("""{"outbounds":[]}""").isEmpty()) }
    @Test fun cottenKeepsAdvancedOptionsAndEnforcesLocalStartup() {
        val rendered = cotten.renderedConfiguration()
        assertTrue(rendered.contains("RESOLVER_TRANSPORT = \"tcp\""))
        assertTrue(rendered.contains("LISTEN_IP = \"127.0.0.1\""))
        assertTrue(rendered.contains("LISTEN_PORT = 18000"))
        assertTrue(rendered.contains("RESOLVER_IP_MODE = \"auto\""))
        assertTrue(rendered.contains("TERMINAL_UI = \"plain\""))
        assertTrue(rendered.contains("STARTUP_MODE = \"resolvers\""))
        assertFalse(rendered.contains("9999"))
    }
    @Test fun rejectsUnsupportedEnginesHooksAndMissingResolvers() {
        assertThrows(IllegalArgumentException::class.java) { awg.copy(engine = "shell").validate() }
        assertThrows(IllegalArgumentException::class.java) { awg.copy(port = 80).validate() }
        assertThrows(IllegalArgumentException::class.java) { awg.copy(configuration = awg.configuration + "PostUp = arbitrary\n").validate() }
        assertThrows(IllegalArgumentException::class.java) { cotten.copy(resolvers = "").validate() }
    }
    @Test fun repeatedReferencesDeduplicateAndConflictingPortsFail() {
        val p = ProfileItem.create(EConfigType.SOCKS).apply { nativeEngine = "amneziawg"; nativeEngineConfig = awg.configuration; serverPort = "18001" }
        fun outbound(profiles: List<ProfileItem>) = CoreConfigContext.ResolvedOutbound("proxy", p, profiles, CoreResolvedType.NORMAL)
        assertEquals(1, NativeEngineConfig.resolve(listOf(outbound(listOf(p, p.copy())))).size)
        assertThrows(IllegalArgumentException::class.java) {
            NativeEngineConfig.resolve(listOf(outbound(listOf(p, p.copy(nativeEngineConfig = awg.configuration + "# other\n")))))
        }
    }
    @Test fun oldSocksProfilesRemainExternal() {
        assertNull(NativeEngineConfig.of(ProfileItem.create(EConfigType.SOCKS).apply { server = "proxy.example"; serverPort = "1080" }))
    }
}
