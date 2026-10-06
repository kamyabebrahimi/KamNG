package com.v2ray.ang.ui.server
import androidx.lifecycle.SavedStateHandle
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SocksEngineViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private class Repository : SocksEngineRepository {
        var profile: ProfileItem? = null
        var failLoad = false
        var failSave = false
        var canDelete = true
        override suspend fun load(guid: String): ProfileItem? {
            if (failLoad) error("load failure")
            return profile
        }
        override suspend fun save(guid: String, profile: ProfileItem): String {
            if (failSave) error("save failure")
            this.profile = profile
            return guid.ifBlank { "stable-guid" }
        }
        override suspend fun delete(guid: String): Boolean = canDelete
    }
    // Test dispatcher APIs have no stable replacement; reevaluate when kotlinx.coroutines.test stabilizes them.
    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeEach fun setup() { Dispatchers.setMain(dispatcher) }
    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterEach fun teardown() { Dispatchers.resetMain() }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun loadSaveAndRestoreUseProfileIdentity() = runTest(dispatcher) {
        val repository = Repository()
        val saved = SavedStateHandle()
        val model = SocksEngineViewModel(saved, repository, dispatcher) { _, _ -> }
        assertTrue(model.state.value.loading)
        model.onAction(SocksEngineAction.Load("", "subscription"))
        advanceUntilIdle()
        model.onAction(SocksEngineAction.Change(SocksEngineAction.Field.NAME, "AWG"))
        model.onAction(SocksEngineAction.Change(SocksEngineAction.Field.ENGINE, "amneziawg"))
        model.onAction(SocksEngineAction.Change(SocksEngineAction.Field.CONFIGURATION, "[Interface]\n[Peer]\n"))
        model.onAction(SocksEngineAction.Save)
        advanceUntilIdle()
        assertEquals("stable-guid", model.state.value.savedGuid)
        assertEquals("subscription", repository.profile?.subscriptionId)
        assertEquals("127.0.0.1", repository.profile?.server)
        val restored = SocksEngineViewModel(saved, repository, dispatcher) { _, _ -> }
        restored.onAction(SocksEngineAction.Load("stable-guid", null))
        advanceUntilIdle()
        assertEquals("amneziawg", restored.state.value.profile.nativeEngine)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun emptyInvalidAndFailureBranchesDoNotReportSaved() = runTest(dispatcher) {
        val repository = Repository()
        val model = SocksEngineViewModel(SavedStateHandle(), repository, dispatcher) { _, _ -> }
        model.onAction(SocksEngineAction.Load("", null)); advanceUntilIdle()
        model.onAction(SocksEngineAction.Save); advanceUntilIdle()
        assertEquals(SocksEngineState.Error.INVALID, model.state.value.error)
        assertNull(model.state.value.savedGuid)
        model.onAction(SocksEngineAction.Change(SocksEngineAction.Field.NAME, "Proxy"))
        model.onAction(SocksEngineAction.Change(SocksEngineAction.Field.ADDRESS, "proxy.example"))
        repository.failSave = true
        model.onAction(SocksEngineAction.Save); advanceUntilIdle()
        assertEquals(SocksEngineState.Error.SAVE, model.state.value.error)
        assertFalse(model.state.value.saving)
    }
    @Test fun changePreservesOldConnectionFieldsAndNativeSettings() {
        val old = ProfileItem.create(EConfigType.SOCKS).apply { remarks = "old"; serverPort = "1080"; server = "proxy.example"; targetStrategy = "UseIPv4"; nativeEngineConfig = "keep"; subscriptionId = "s" }
        val updated = SocksEngineViewModel.change(old, SocksEngineAction.Field.NAME, "new")
        assertEquals("old", old.remarks); assertEquals("new", updated.remarks)
        assertEquals("keep", updated.nativeEngineConfig); assertEquals("UseIPv4", updated.targetStrategy)
        assertTrue(SocksEngineViewModel.isValid(updated))
        assertFalse(SocksEngineViewModel.isValid(updated.copy(serverPort = "70000")))
    }
}
