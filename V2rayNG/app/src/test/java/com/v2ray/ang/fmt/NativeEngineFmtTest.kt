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
}
