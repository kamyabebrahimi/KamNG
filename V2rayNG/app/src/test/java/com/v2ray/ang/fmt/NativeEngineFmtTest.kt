package com.v2ray.ang.fmt
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class NativeEngineFmtTest {
 @Test fun nativeProfileRoundTripPreservesConfiguration() {
  val original=ProfileItem.create(EConfigType.SOCKS).apply {
   remarks="KamNG"; server="127.0.0.1"; serverPort="18000"; nativeEngine="cottendns"
   nativeEngineConfig="DOMAINS=test.example\nENCRYPTION_KEY=example"
   nativeEngineResolvers="1.1.1.1\n8.8.8.8"
  }
  val result=NativeEngineFmt.parse(NativeEngineFmt.toUri(original))
  assertNotNull(result)
  assertEquals(original.nativeEngineConfig,result?.nativeEngineConfig)
  assertEquals(original.nativeEngineResolvers,result?.nativeEngineResolvers)
  assertEquals(original.serverPort,result?.serverPort)
 }
 @Test fun invalidLinksAreRejected() {
  assertNull(NativeEngineFmt.parse("kamng://invalid!"))
  assertNull(NativeEngineFmt.parse("socks://example"))
  assertThrows(IllegalArgumentException::class.java){NativeEngineFmt.toUri(ProfileItem.create(EConfigType.SOCKS))}
 }

 @Test fun rawAmneziaConfigurationKeepsEveryAdvancedSettingAndPeer() {
  val file=amneziaFile() + "\n[Peer]\nPublicKey=$key\nEndpoint=[2001:db8::1]:51820\nAllowedIPs=::/0\n"
  val profile=NativeEngineFmt.parseAmneziaConfiguration(file)
  assertEquals("amneziawg",profile.nativeEngine)
  assertEquals(EConfigType.SOCKS,profile.configType)
  assertEquals("127.0.0.1",profile.server)
  assertEquals("18001",profile.serverPort)
  assertEquals("My tunnel",profile.remarks)
  assertEquals(file,profile.nativeEngineConfig)
  assertEquals(file,NativeEngineFmt.parse(NativeEngineFmt.toUri(profile))?.nativeEngineConfig)
 }
 @Test fun detectsBOMCaseAndCommentsButNeverMistakesOrdinaryWireGuard() {
  val file="\uFEFF; exported file\n"+amneziaFile().replace("[Interface]","[interface]").replace("[Peer]","[peer]")
  assertTrue(NativeEngineFmt.isTunnelConfiguration(file))
  assertTrue(NativeEngineFmt.isAmneziaConfiguration(file))
  assertEquals(file.removePrefix("\uFEFF"),NativeEngineFmt.parseAmneziaConfiguration(file).nativeEngineConfig)
  assertFalse(NativeEngineFmt.isAmneziaConfiguration(amneziaFile().replace(advanced,"")))
  assertTrue(NativeEngineFmt.isAmneziaConfiguration("# AmneziaWG\n"+amneziaFile().replace(advanced,"")))
  assertFalse(NativeEngineFmt.isTunnelConfiguration(""))
  assertFalse(NativeEngineFmt.isAmneziaConfiguration("# Jc = 7\n[Interface]\n[Peer]\n"))
 }
 @Test fun invalidNativeFilesCannotFallBackToOrdinaryWireGuard() {
  for(file in listOf(amneziaFile().replace(key,"bad-key"),amneziaFile().replace("Endpoint=example.test:51820","Endpoint=example.test"),amneziaFile().replace("Address=10.0.0.2/32","Address="),amneziaFile()+"PostUp=echo unsupported\n")) {
   assertTrue(NativeEngineFmt.isAmneziaConfiguration(file))
   assertThrows(IllegalArgumentException::class.java){NativeEngineFmt.parseAmneziaConfiguration(file)}
  }
 }
 private val key=java.util.Base64.getEncoder().encodeToString(ByteArray(32){7})
 private val advanced="Jc=7\nJmin=10\nJmax=50\nS1=12\nS2=13\nH1=123\nH2=456\nI1=<b 0xdeadbeef>\n"
 private fun amneziaFile()="# Name = My tunnel\n[Interface]\nPrivateKey=$key\nAddress=10.0.0.2/32\nDNS=1.1.1.1\n"+advanced+"[Peer]\nPublicKey=$key\nEndpoint=example.test:51820\nAllowedIPs=0.0.0.0/0\n"

 @Test fun inlineCommentsAndNativeOnlyOptionsRemainUsable() {
  val file=amneziaFile().replace("[Interface]","[Interface] # inbounds outbounds routing").replace("PrivateKey=$key","PrivateKey=$key # identity")
  val profile=NativeEngineFmt.parseAmneziaConfiguration(file)
  assertEquals(file,profile.nativeEngineConfig)
  val alternate=amneziaFile().replace(advanced,"disable_cookies=true\n")
  assertTrue(NativeEngineFmt.isAmneziaConfiguration(alternate))
  assertEquals(alternate,NativeEngineFmt.parseAmneziaConfiguration(alternate).nativeEngineConfig)
 }
}
