package com.v2ray.ang.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeEngineProbeTest {
    @Test
    fun startsTransportBeforePassingRemappedContentToConsumer() = runTest {
        val events = mutableListOf<String>()
        val value = NativeEngineProbe.runOwnedProbe(
            content = "remapped-exit-configuration",
            start = { events += "start" },
            stop = { events += "stop" }
        ) { content ->
            assertEquals(listOf("start"), events)
            assertEquals("remapped-exit-configuration", content)
            events += "probe"
            42L
        }
        assertEquals(42L, value)
        assertEquals(listOf("start", "probe", "stop"), events)
    }

    @Test
    fun closesTransportWhenStartupFailsWithoutRunningConsumer() = runTest {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("startup")
        try {
            NativeEngineProbe.runOwnedProbe(
                content = "configuration",
                start = { events += "start"; throw failure },
                stop = { events += "stop" }
            ) { events += "probe"; 0L }
            throw AssertionError("Expected startup failure")
        } catch (error: IllegalStateException) {
            assertTrue(error === failure)
        }
        assertEquals(listOf("start", "stop"), events)
    }

    @Test
    fun closesTransportWhenConsumerFails() = runTest {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("probe")
        try {
            NativeEngineProbe.runOwnedProbe(
                content = "configuration",
                start = { events += "start" },
                stop = { events += "stop" }
            ) { events += "probe"; throw failure }
            throw AssertionError("Expected probe failure")
        } catch (error: IllegalStateException) {
            assertTrue(error === failure)
        }
        assertEquals(listOf("start", "probe", "stop"), events)
    }

    @Test
    fun closesTransportOnSuspendedConsumerCancellation() = runTest {
        val events = mutableListOf<String>()
        val job = launch {
            NativeEngineProbe.runOwnedProbe(
                content = "configuration",
                start = { events += "start" },
                stop = { events += "stop" }
            ) { events += "probe"; awaitCancellation() }
        }
        runCurrent()
        assertEquals(listOf("start", "probe"), events)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("start", "probe", "stop"), events)
    }
}
