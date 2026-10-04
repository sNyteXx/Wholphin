package com.github.damontecres.wholphin.custom.hiddenvault.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.custom.hiddenvault.HiddenVault
import com.github.damontecres.wholphin.custom.hiddenvault.session.VaultPinStore
import com.github.damontecres.wholphin.preferences.AppClickablePreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.preferences.ClickPreference
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The way in: an unremarkable "Device" line in Settings → About. It looks like the version line
 * next to it (no arrow, no hint of a sub page); selecting it asks for the vault PIN, or lets the
 * user choose one the first time.
 */
object HiddenVaultSettings {
    val Entry =
        AppClickablePreference<AppPreferences>(
            title = R.string.hidden_vault_entry_title,
        )
}

@Composable
fun HiddenVaultSettingsEntry(
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
    viewModel: VaultPinViewModel = hiltViewModel(),
) {
    val summary = remember { "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}" }
    ClickPreference(
        title = stringResource(R.string.hidden_vault_entry_title),
        summary = summary,
        onClick = viewModel::openPrivateArea,
        interactionSource = interactionSource,
        modifier = modifier,
    )
    VaultPinPrompt(viewModel)
}

/** Shows the PIN dialog while [viewModel] asks for one */
@Composable
fun VaultPinPrompt(viewModel: VaultPinViewModel) {
    val state by viewModel.state.collectAsState()
    val prompt = state ?: return
    VaultPinDialog(
        title =
            stringResource(
                when (prompt.stage) {
                    PinStage.VERIFY -> R.string.hidden_vault_pin_title
                    PinStage.CREATE -> R.string.hidden_vault_pin_create
                    PinStage.CONFIRM -> R.string.hidden_vault_pin_confirm
                },
            ),
        message = prompt.message,
        attempt = prompt.attempt,
        enabled = !prompt.busy,
        onSubmit = viewModel::submit,
        onDismissRequest = viewModel::dismiss,
    )
}

enum class PinStage { VERIFY, CREATE, CONFIRM }

data class PinPrompt(
    val stage: PinStage,
    val message: String? = null,
    val attempt: Int = 0,
    val busy: Boolean = false,
    /** What happens after the PIN was entered (or set) */
    val purpose: PinPurpose = PinPurpose.OPEN_PRIVATE_AREA,
    val firstEntry: String? = null,
)

enum class PinPurpose { OPEN_PRIVATE_AREA, CHANGE_PIN }

@HiltViewModel
class VaultPinViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val vault: HiddenVault,
        private val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow<PinPrompt?>(null)
        val state: StateFlow<PinPrompt?> = _state

        fun openPrivateArea() {
            val scope = vault.activeScope.value ?: return
            _state.value =
                if (vault.pins.isSet(scope)) PinPrompt(PinStage.VERIFY) else PinPrompt(PinStage.CREATE)
        }

        fun changePin() {
            _state.value = PinPrompt(PinStage.CREATE, purpose = PinPurpose.CHANGE_PIN)
        }

        fun dismiss() {
            _state.value = null
        }

        fun submit(pin: String) {
            val prompt = _state.value ?: return
            val scope = vault.activeScope.value ?: return dismiss()
            _state.update { it?.copy(busy = true) }
            viewModelScope.launch {
                when (prompt.stage) {
                    PinStage.VERIFY -> {
                        val result = withContext(WholphinDispatchers.Default) { vault.pins.verify(scope, pin) }
                        when (result) {
                            VaultPinStore.Result.Success -> {
                                done(prompt)
                            }

                            VaultPinStore.Result.NotSet -> {
                                _state.value = PinPrompt(PinStage.CREATE, purpose = prompt.purpose)
                            }

                            is VaultPinStore.Result.Wrong -> {
                                retry(
                                    prompt,
                                    result.lockedUntilMs?.let(::lockedMessage) ?: context.getString(R.string.hidden_vault_pin_wrong),
                                )
                            }

                            is VaultPinStore.Result.LockedOut -> {
                                retry(prompt, lockedMessage(result.untilMs))
                            }
                        }
                    }

                    PinStage.CREATE -> {
                        _state.value =
                            prompt.copy(
                                stage = PinStage.CONFIRM,
                                firstEntry = pin,
                                attempt = prompt.attempt + 1,
                                busy = false,
                                message = null,
                            )
                    }

                    PinStage.CONFIRM -> {
                        if (pin == prompt.firstEntry) {
                            withContext(WholphinDispatchers.Default) { vault.pins.set(scope, pin) }
                            done(prompt)
                        } else {
                            _state.value =
                                prompt.copy(
                                    stage = PinStage.CREATE,
                                    firstEntry = null,
                                    attempt = prompt.attempt + 1,
                                    busy = false,
                                    message = context.getString(R.string.hidden_vault_pin_mismatch),
                                )
                        }
                    }
                }
            }
        }

        private fun retry(
            prompt: PinPrompt,
            message: String,
        ) {
            _state.value = prompt.copy(attempt = prompt.attempt + 1, busy = false, message = message)
        }

        private fun lockedMessage(untilMs: Long): String {
            val seconds = ((untilMs - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
            return context.getString(R.string.hidden_vault_pin_locked, seconds.toInt())
        }

        private fun done(prompt: PinPrompt) {
            _state.value = null
            if (prompt.purpose == PinPurpose.OPEN_PRIVATE_AREA) {
                val scope = vault.activeScope.value ?: return
                vault.session.openPrivateArea(scope)
                navigationManager.navigateTo(Destination.HiddenVault(VaultRoute.PrivateArea))
            }
        }
    }
