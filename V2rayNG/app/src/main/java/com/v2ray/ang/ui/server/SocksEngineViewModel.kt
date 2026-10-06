package com.v2ray.ang.ui.server

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.NativeEngineConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SocksEngineState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val profile: ProfileItem = ProfileItem.create(EConfigType.SOCKS),
    val error: Error? = null,
    val savedGuid: String? = null,
    val deletedGuid: String? = null,
) {
    enum class Error { LOAD, INVALID, SAVE, DELETE }
}

sealed interface SocksEngineAction {
    data class Load(val guid: String, val subscriptionId: String?) : SocksEngineAction
    data class Change(val field: Field, val value: String) : SocksEngineAction
    data object Save : SocksEngineAction
    data object Delete : SocksEngineAction
    enum class Field { NAME, ADDRESS, PORT, USERNAME, PASSWORD, ENGINE, CONFIGURATION, RESOLVERS, DIAL_MODE, TARGET_STRATEGY }
}

interface SocksEngineRepository {
    suspend fun load(guid: String): ProfileItem?
    suspend fun save(guid: String, profile: ProfileItem): String
    suspend fun delete(guid: String): Boolean
}

private object StoredSocksEngineRepository : SocksEngineRepository {
    override suspend fun load(guid: String) = withContext(Dispatchers.IO) { MmkvManager.decodeServerConfig(guid) }
    override suspend fun save(guid: String, profile: ProfileItem): String = withContext(Dispatchers.IO) {
        profile.description = AngConfigManager.generateDescription(profile)
        MmkvManager.encodeServerConfig(guid, profile)
    }
    override suspend fun delete(guid: String): Boolean = withContext(Dispatchers.IO) {
        if (guid.isBlank() || MmkvManager.getSelectServer() == guid) false else {
            MmkvManager.removeServer(guid)
            true
        }
    }
}

class SocksEngineViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    private val repository: SocksEngineRepository = StoredSocksEngineRepository,
    private val parserDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val reportFailure: (String, Exception) -> Unit = { message, error -> LogUtil.e(AppConfig.TAG, message, error) },
) : ViewModel() {
    private val mutableState = MutableStateFlow(SocksEngineState())
    val state: StateFlow<SocksEngineState> = mutableState.asStateFlow()
    private var guid = ""
    private var loaded = false
    private var operation: Job? = null
    private var draftSave: Job? = null

    fun onAction(action: SocksEngineAction) {
        when (action) {
            is SocksEngineAction.Load -> load(action)
            is SocksEngineAction.Change -> {
                if (mutableState.value.loading || mutableState.value.saving || mutableState.value.error == SocksEngineState.Error.LOAD) return
                val changed = change(mutableState.value.profile, action.field, action.value)
                mutableState.value = mutableState.value.copy(profile = changed, error = null)
                draftSave?.cancel()
                draftSave = viewModelScope.launch {
                    val json = withContext(parserDispatcher) { JsonUtil.toJson(changed) }
                    savedState["profile"] = json
                }
            }
            SocksEngineAction.Save -> save()
            SocksEngineAction.Delete -> delete()
        }
    }

    private fun load(action: SocksEngineAction.Load) {
        if (loaded) return
        loaded = true
        guid = action.guid
        operation = viewModelScope.launch {
            try {
                val profile = withContext(parserDispatcher) {
                    savedState.get<String>("profile")?.let { JsonUtil.fromJsonSafe(it, ProfileItem::class.java) }
                } ?: repository.load(guid) ?: ProfileItem.create(EConfigType.SOCKS).apply {
                    subscriptionId = action.subscriptionId.orEmpty()
                    serverPort = "1080"
                }
                mutableState.value = SocksEngineState(loading = false, profile = profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure("SOCKS engine editor load profile=$guid", e)
                mutableState.value = SocksEngineState(loading = false, error = SocksEngineState.Error.LOAD)
            }
        }
    }

    private fun save() {
        if (mutableState.value.loading || mutableState.value.saving || mutableState.value.error == SocksEngineState.Error.LOAD) return
        val profile = mutableState.value.profile.copy()
        mutableState.value = mutableState.value.copy(saving = true, error = null)
        operation = viewModelScope.launch {
            val valid = withContext(parserDispatcher) { isValid(profile) }
            if (!valid) {
                mutableState.value = mutableState.value.copy(saving = false, error = SocksEngineState.Error.INVALID)
                return@launch
            }
            try {
                val id = repository.save(guid, profile)
                guid = id
                mutableState.value = mutableState.value.copy(saving = false, savedGuid = id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure("SOCKS engine editor save profile=$guid", e)
                mutableState.value = mutableState.value.copy(saving = false, error = SocksEngineState.Error.SAVE)
            }
        }
    }

    private fun delete() {
        if (mutableState.value.loading || mutableState.value.saving || mutableState.value.error == SocksEngineState.Error.LOAD || guid.isBlank()) return
        mutableState.value = mutableState.value.copy(saving = true, error = null)
        operation = viewModelScope.launch {
            try {
                if (repository.delete(guid)) mutableState.value = mutableState.value.copy(saving = false, deletedGuid = guid)
                else mutableState.value = mutableState.value.copy(saving = false, error = SocksEngineState.Error.DELETE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure("SOCKS engine editor delete profile=$guid", e)
                mutableState.value = mutableState.value.copy(saving = false, error = SocksEngineState.Error.DELETE)
            }
        }
    }

    companion object {
        internal fun change(profile: ProfileItem, field: SocksEngineAction.Field, value: String): ProfileItem = when (field) {
            SocksEngineAction.Field.NAME -> profile.copy(remarks = value)
            SocksEngineAction.Field.ADDRESS -> profile.copy(server = value)
            SocksEngineAction.Field.PORT -> profile.copy(serverPort = value)
            SocksEngineAction.Field.USERNAME -> profile.copy(username = value)
            SocksEngineAction.Field.PASSWORD -> profile.copy(password = value)
            SocksEngineAction.Field.CONFIGURATION -> profile.copy(nativeEngineConfig = value)
            SocksEngineAction.Field.RESOLVERS -> profile.copy(nativeEngineResolvers = value)
            SocksEngineAction.Field.DIAL_MODE -> profile.copy(dialMode = value)
            SocksEngineAction.Field.TARGET_STRATEGY -> profile.copy(targetStrategy = value)
            SocksEngineAction.Field.ENGINE -> if (value.isBlank()) profile.copy(nativeEngine = null) else profile.copy(
                nativeEngine = value, server = "127.0.0.1",
                serverPort = if (value == "amneziawg") "18001" else "18000",
                username = null, password = null,
            )
        }

        internal fun isValid(profile: ProfileItem): Boolean = try {
            require(profile.remarks.isNotBlank())
            require((profile.serverPort?.toIntOrNull() ?: 0) in 1..65535)
            if (profile.nativeEngine.isNullOrBlank()) require(!profile.server.isNullOrBlank())
            else NativeEngineConfig.of(profile)
            true
        } catch (_: Exception) { false }
    }
}
