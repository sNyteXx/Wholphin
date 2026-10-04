package com.github.damontecres.wholphin.custom.hiddenvault

import com.github.damontecres.wholphin.custom.hiddenvault.data.HiddenContentService
import com.github.damontecres.wholphin.custom.hiddenvault.data.VaultScope
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultSettings
import com.github.damontecres.wholphin.custom.hiddenvault.session.VaultLockReason
import com.github.damontecres.wholphin.custom.hiddenvault.session.VaultPinStore
import com.github.damontecres.wholphin.custom.hiddenvault.session.VaultSessionManager
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.HiddenVaultRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The hidden content vault of the signed in account: its rules, its session and its PIN.
 *
 * This is the one object the rest of the app talks to. It follows the active server and user,
 * keeps one [HiddenContentService] for them, and locks every vault the moment the account changes.
 */
class HiddenVault(
    private val serviceFactory: (VaultScope) -> HiddenContentService,
    val session: VaultSessionManager,
    val pins: VaultPinStore,
    private val appScope: CoroutineScope,
    /**
     * The signed in account right now. Read on every access so the first requests after a user
     * switch are already judged by the new user's rules, not after an asynchronous update.
     */
    private val scopeSource: (() -> VaultScope?)? = null,
) : HiddenVaultRuntime {
    private val _activeScope = MutableStateFlow<VaultScope?>(null)

    /** The signed in server and user, or null when signed out */
    val activeScope: StateFlow<VaultScope?> = _activeScope.asStateFlow()

    @Volatile
    private var service: HiddenContentService? = null
    private val lock = Any()
    private var pendingLibraryRefresh: Job? = null

    init {
        session.settingsFor = { scope -> serviceIfLoaded(scope)?.config?.settings ?: VaultSettings() }
    }

    /** Sign in, sign out, user switch and server switch all land here */
    fun setActiveScope(scope: VaultScope?) {
        val next = scope?.takeIf { it.isValid }
        if (_activeScope.value == next) return
        synchronized(lock) {
            _activeScope.value = next
            service = null
        }
        session.onActiveScopeChanged(next)
    }

    private fun serviceIfLoaded(scope: VaultScope): HiddenContentService? = service?.takeIf { it.scope == scope }

    /** The service of the signed in account, created on first use */
    fun currentService(): HiddenContentService? {
        scopeSource?.let { setActiveScope(it()) }
        val scope = _activeScope.value ?: return null
        return synchronized(lock) {
            service?.takeIf { it.scope == scope } ?: serviceFactory(scope).also { service = it }
        }
    }

    override suspend fun activeService(): HiddenContentService? {
        val service = currentService() ?: return null
        service.ensureReady()
        return service.takeIf { it.isActive }
    }

    override fun loadedService(): HiddenContentService? = currentService()

    override fun enteredVaults(): Set<String> {
        scopeSource?.let { setActiveScope(it()) }
        return session.enteredVaults(_activeScope.value)
    }

    override fun noteVaultActivity(vaultId: String) {
        _activeScope.value?.let { session.touch(it, vaultId) }
    }

    /** The server reported library changes; tags may have changed with them */
    fun onLibraryChanged() {
        synchronized(lock) {
            if (pendingLibraryRefresh?.isActive == true) return
            pendingLibraryRefresh =
                appScope.launch {
                    // Coalesces the burst of events a scan or a metadata edit sends
                    delay(LIBRARY_CHANGE_DEBOUNCE_MS)
                    currentService()?.refreshInBackground()
                }
        }
    }

    /** After a config save: vaults that no longer exist can't stay open */
    fun onConfigSaved() {
        val scope = _activeScope.value ?: return
        val config = serviceIfLoaded(scope)?.config ?: return
        session.unlockedVaults(scope).forEach { vaultId ->
            if (config.vault(vaultId) == null) session.lock(scope, vaultId, VaultLockReason.CONFIG_CHANGED)
        }
    }

    companion object {
        const val LIBRARY_CHANGE_DEBOUNCE_MS = 30_000L
    }
}
