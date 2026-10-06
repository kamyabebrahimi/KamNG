package com.v2ray.ang.ui.server

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField

/** Add SOCKS -> choose AmneziaWG/CottenDNS to create a routed native-engine profile. */
class ServerSocksActivity : BaseComponentActivity() {
    private val model: SocksEngineViewModel by viewModels()
    private val guid by lazy { intent.getStringExtra("guid").orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.onAction(SocksEngineAction.Load(guid, intent.getStringExtra("subscriptionId")))
    }

    @Composable
    override fun ScreenContent() {
        val state by model.state.collectAsStateWithLifecycle()
        var confirmDelete by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(state.savedGuid, state.deletedGuid) {
            state.savedGuid?.let { ProfileEditorResult.run { finishSaved(it, intent.getBooleanExtra("isRunning", false)) } }
            state.deletedGuid?.let { ProfileEditorResult.run { finishDeleted(it) } }
        }
        val profile = state.profile
        fun change(field: SocksEngineAction.Field, value: String) = model.onAction(SocksEngineAction.Change(field, value))
        val labels = listOf(stringResource(R.string.kamng_engine_socks), stringResource(R.string.kamng_engine_awg), stringResource(R.string.kamng_engine_cotten))
        val engines = listOf("", "amneziawg", "cottendns")
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.kamng_engine_editor),
                    onBackClick = { finish() },
                    actions = {
                        if (guid.isNotBlank() && !intent.getBooleanExtra("isRunning", false)) {
                            IconButton(onClick = { confirmDelete = true }, enabled = !state.loading && !state.saving) {
                                Icon(painterResource(R.drawable.ic_delete_24dp), stringResource(R.string.acc_delete))
                            }
                        }
                        IconButton(onClick = { model.onAction(SocksEngineAction.Save) }, enabled = !state.loading && !state.saving) {
                            Icon(painterResource(R.drawable.ic_fab_check), stringResource(R.string.acc_save))
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).imePadding()
                    .verticalScroll(rememberScrollState()).padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.loading || state.saving) CircularProgressIndicator(Modifier.padding(16.dp))
                state.error?.let { error ->
                    Text(stringResource(when (error) {
                        SocksEngineState.Error.LOAD -> R.string.kamng_engine_load_failed
                        SocksEngineState.Error.INVALID -> R.string.kamng_engine_invalid
                        SocksEngineState.Error.SAVE -> R.string.kamng_engine_save_failed
                        SocksEngineState.Error.DELETE -> R.string.kamng_engine_delete_failed
                    }), Modifier.padding(16.dp))
                }
                if (!state.loading) {
                    FormTextField(stringResource(R.string.server_lab_remarks), profile.remarks, { change(SocksEngineAction.Field.NAME, it) }, enabled = !state.saving)
                    FormDropdownField(stringResource(R.string.kamng_engine_label),
                        labels[engines.indexOf(profile.nativeEngine.orEmpty()).coerceAtLeast(0)], labels,
                        { change(SocksEngineAction.Field.ENGINE, engines[labels.indexOf(it)]) }, enabled = !state.saving)
                    if (profile.nativeEngine.isNullOrBlank()) {
                        FormTextField(stringResource(R.string.server_lab_address), profile.server.orEmpty(), { change(SocksEngineAction.Field.ADDRESS, it) }, enabled = !state.saving)
                        FormTextField(stringResource(R.string.server_lab_security4), profile.username.orEmpty(), { change(SocksEngineAction.Field.USERNAME, it) }, enabled = !state.saving)
                        FormTextField(stringResource(R.string.server_lab_id4), profile.password.orEmpty(), { change(SocksEngineAction.Field.PASSWORD, it) }, enabled = !state.saving)
                        FormTextField(stringResource(R.string.server_lab_dial_mode), profile.dialMode.orEmpty(), { change(SocksEngineAction.Field.DIAL_MODE, it) }, enabled = !state.saving)
                    } else {
                        Text(stringResource(R.string.kamng_engine_help), Modifier.padding(horizontal = 16.dp))
                        FormTextField(stringResource(R.string.kamng_engine_configuration), profile.nativeEngineConfig.orEmpty(),
                            { change(SocksEngineAction.Field.CONFIGURATION, it) }, enabled = !state.saving, maxLines = 15)
                        if (profile.nativeEngine == "cottendns") {
                            FormTextField(stringResource(R.string.kamng_engine_resolvers), profile.nativeEngineResolvers.orEmpty(),
                                { change(SocksEngineAction.Field.RESOLVERS, it) }, enabled = !state.saving, maxLines = 8)
                        }
                    }
                    FormTextField(stringResource(R.string.server_lab_port), profile.serverPort.orEmpty(),
                        { change(SocksEngineAction.Field.PORT, it) }, enabled = !state.saving)
                    FormDropdownField(stringResource(R.string.server_lab_target_strategy), profile.targetStrategy ?: "AsIs",
                        listOf("AsIs", "UseIP", "UseIPv4", "UseIPv6", "ForceIP", "ForceIPv4", "ForceIPv6"),
                        { change(SocksEngineAction.Field.TARGET_STRATEGY, it) }, enabled = !state.saving)
                }
            }
        }
        if (confirmDelete) DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_profile), itemName = profile.remarks,
            onConfirm = { confirmDelete = false; model.onAction(SocksEngineAction.Delete) },
            onDismiss = { confirmDelete = false },
        )
    }
}
