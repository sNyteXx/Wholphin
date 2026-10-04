package com.github.damontecres.wholphin.custom.hiddenvault.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.custom.hiddenvault.HiddenVault
import com.github.damontecres.wholphin.custom.hiddenvault.data.VaultDeviceSettings
import com.github.damontecres.wholphin.custom.hiddenvault.model.HiddenVaultConfig
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultSettings
import com.github.damontecres.wholphin.custom.hiddenvault.session.VaultLockReason
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.preferences.ClickPreference
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * The private area: the vaults to open on top, their setup and the session settings below.
 * Styled like Wholphin's settings panel.
 */
@Composable
fun PrivateAreaPage(
    modifier: Modifier = Modifier,
    viewModel: PrivateAreaViewModel = hiltViewModel(),
    pinViewModel: VaultPinViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    if (!state.open) {
        LockedPage(viewModel.navigationManager, modifier)
        return
    }
    val config = state.config
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.tryRequestFocus() }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            modifier =
                Modifier
                    .fillMaxWidth(.4f)
                    .fillMaxHeight()
                    .align(Alignment.TopEnd)
                    .background(MaterialTheme.colorScheme.surface),
        ) {
            item { SectionTitle(stringResource(R.string.hidden_vault_areas)) }
            if (config.vaults.isEmpty()) {
                item { SectionHint(stringResource(R.string.hidden_vault_no_areas)) }
            }
            items(config.vaults, key = { "open-${it.id}" }) { vault ->
                ClickPreference(
                    title = vault.name,
                    summary = vault.libraries.joinToString(", ") { it.name },
                    onClick = { viewModel.openVault(vault.id) },
                    modifier = if (vault == config.vaults.first()) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }

            item { SectionTitle(stringResource(R.string.hidden_vault_settings)) }
            items(config.vaults, key = { "edit-${it.id}" }) { vault ->
                ClickPreference(
                    title = stringResource(R.string.hidden_vault_edit_area, vault.name),
                    summary = vault.libraries.joinToString(", ") { "${it.name} (${it.tags.size})" },
                    onClick = { viewModel.navigationManager.navigateTo(Destination.HiddenVault(VaultRoute.Editor(vault.id))) },
                )
            }
            item {
                ClickPreference(
                    title = stringResource(R.string.hidden_vault_add_area),
                    onClick = { viewModel.navigationManager.navigateTo(Destination.HiddenVault(VaultRoute.Editor(null))) },
                    modifier = if (config.vaults.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
            item {
                val minutes = config.settings.autoLockMinutes
                ClickPreference(
                    title = stringResource(R.string.hidden_vault_auto_lock),
                    summary = stringResource(R.string.hidden_vault_minutes, minutes),
                    onClick = {
                        val choices = VaultSettings.AUTO_LOCK_CHOICES
                        val next = choices[(choices.indexOf(minutes) + 1).mod(choices.size)]
                        viewModel.updateSettings { it.copy(autoLockMinutes = next) }
                    },
                )
            }
            item {
                ClickPreference(
                    title = stringResource(R.string.hidden_vault_lock_on_leave),
                    summary = onOff(config.settings.lockOnLeave),
                    onClick = { viewModel.updateSettings { it.copy(lockOnLeave = !it.lockOnLeave) } },
                )
            }
            item {
                ClickPreference(
                    title = stringResource(R.string.hidden_vault_hide_watched),
                    summary = onOff(config.settings.hideWatched),
                    onClick = { viewModel.updateSettings { it.copy(hideWatched = !it.hideWatched) } },
                )
            }

            item { SectionTitle(stringResource(R.string.hidden_vault_this_device)) }
            item {
                ClickPreference(
                    title = stringResource(R.string.hidden_vault_sync),
                    summary = onOff(state.device.syncEnabled),
                    onClick = viewModel::toggleSync,
                )
            }
            item {
                ClickPreference(
                    title = stringResource(R.string.hidden_vault_change_pin),
                    onClick = pinViewModel::changePin,
                )
            }
            item {
                ClickPreference(
                    title = stringResource(R.string.hidden_vault_lock_now),
                    onClick = viewModel::lockNow,
                )
            }
        }
    }
    VaultPinPrompt(pinViewModel)
}

@Composable
private fun onOff(value: Boolean) = stringResource(if (value) R.string.hidden_vault_on else R.string.hidden_vault_off)

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Start,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
internal fun SectionHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(8.dp),
    )
}

/**
 * Shown for a vault page whose session is gone (a restored back stack, a lock): nothing of the
 * vault, and the page leaves by itself.
 */
@Composable
internal fun LockedPage(
    navigationManager: NavigationManager,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { navigationManager.goToHome() }
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
}

data class PrivateAreaState(
    val open: Boolean,
    val config: HiddenVaultConfig = HiddenVaultConfig.EMPTY,
    val device: VaultDeviceSettings = VaultDeviceSettings(),
)

@HiltViewModel
class PrivateAreaViewModel
    @Inject
    constructor(
        private val vault: HiddenVault,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val scope = vault.activeScope.value
        private val service = vault.currentService()
        private val _state =
            MutableStateFlow(PrivateAreaState(open = vault.session.isPrivateAreaOpen(scope) && service != null))
        val state: StateFlow<PrivateAreaState> = _state

        /** Rules changed during this visit: the normal screens reload once on the way out */
        private var rulesChanged = false
        private val fingerprintOnEntry = service?.fingerprint

        init {
            if (service != null) {
                combine(service.generation, vault.session.changes) { _, _ -> }
                    .onEach {
                        _state.value =
                            PrivateAreaState(
                                open = vault.session.isPrivateAreaOpen(scope),
                                config = service.config,
                                device = service.deviceSettings,
                            )
                        rulesChanged = service.fingerprint != fingerprintOnEntry
                    }.launchIn(viewModelScope)
                viewModelScope.launchIO { service.ensureSynced() }
            }
        }

        fun openVault(vaultId: String) {
            val scope = scope ?: return
            vault.session.touchPrivateArea()
            vault.session.unlock(scope, vaultId)
            navigationManager.navigateTo(Destination.HiddenVault(VaultRoute.Home(vaultId)))
        }

        fun updateSettings(change: (VaultSettings) -> VaultSettings) {
            val service = service ?: return
            vault.session.touchPrivateArea()
            viewModelScope.launchIO {
                service.saveConfig(service.config.copy(settings = change(service.config.settings)))
            }
        }

        fun toggleSync() {
            val service = service ?: return
            vault.session.touchPrivateArea()
            viewModelScope.launchIO {
                service.saveDeviceSettings(service.deviceSettings.copy(syncEnabled = !service.deviceSettings.syncEnabled))
            }
        }

        fun lockNow() {
            vault.session.lockAll(VaultLockReason.MANUAL)
        }

        override fun onCleared() {
            // Leaving the private area closes it; the next visit asks for the PIN again
            vault.session.closePrivateArea()
            if (rulesChanged) Handler(Looper.getMainLooper()).post { navigationManager.reloadHome() }
        }
    }
