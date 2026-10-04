package com.github.damontecres.wholphin.custom.hiddenvault.session

import com.github.damontecres.wholphin.custom.hiddenvault.data.VaultScope
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class VaultLockReason {
    MANUAL,
    TIMEOUT,
    LEFT_VAULT,
    ACCOUNT_CHANGED,
    CONFIG_CHANGED,
}

data class VaultLockEvent(
    val scope: VaultScope,
    /** The vault that locked, or null for the private area */
    val vaultId: String?,
    val reason: VaultLockReason,
)

/**
 * Which vaults are open right now. Memory only, on purpose.
 *
 * Nothing here is ever written down, so a restart, a crash or the process being killed always
 * comes back locked. Unlocking never changes what the normal app shows; it only lets the vault's
 * own screens open, and item specific calls through while the vault is [entered][enter].
 *
 * The private area (vault list and settings, reached with the PIN) is a session of its own: it
 * times out and locks the same way.
 */
class VaultSessionManager(
    private val clock: () -> Long = System::currentTimeMillis,
    /** The session settings of an account, read on every check so changes apply at once */
    var settingsFor: (VaultScope) -> VaultSettings = { VaultSettings() },
) {
    private class Unlock(
        val unlockedAt: Long,
        var lastActivity: Long,
        var entered: Int = 0,
    )

    private class PrivateArea(
        val scope: VaultScope,
        var lastActivity: Long,
    )

    private val lock = Any()
    private val unlocks = mutableMapOf<Pair<VaultScope, String>, Unlock>()
    private var privateArea: PrivateArea? = null

    private val _lockEvents = MutableSharedFlow<VaultLockEvent>(extraBufferCapacity = 64)

    /** One event per vault (or private area) that locks, with the reason */
    val lockEvents: SharedFlow<VaultLockEvent> = _lockEvents.asSharedFlow()

    private val _changes = MutableStateFlow(0)

    /** Changes on every unlock, lock, enter and leave */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun changed() {
        _changes.value = _changes.value + 1
    }

    val hasUnlocked: Boolean get() = synchronized(lock) { unlocks.isNotEmpty() || privateArea != null }

    fun isUnlocked(
        scope: VaultScope,
        vaultId: String,
    ): Boolean = synchronized(lock) { unlocks.containsKey(scope to vaultId) }

    fun isEntered(
        scope: VaultScope,
        vaultId: String,
    ): Boolean = synchronized(lock) { (unlocks[scope to vaultId]?.entered ?: 0) > 0 }

    /** The vaults of [scope] that are unlocked and on screen */
    fun enteredVaults(scope: VaultScope?): Set<String> {
        if (scope == null) return emptySet()
        return synchronized(lock) {
            unlocks.entries
                .filter { it.key.first == scope && it.value.entered > 0 }
                .mapTo(mutableSetOf()) { it.key.second }
        }
    }

    /** The vaults of [scope] that are unlocked, on screen or not */
    fun unlockedVaults(scope: VaultScope?): Set<String> {
        if (scope == null) return emptySet()
        return synchronized(lock) { unlocks.keys.filter { it.first == scope }.mapTo(mutableSetOf()) { it.second } }
    }

    // ---------------------------------------------------------------------------------------
    // Private area
    // ---------------------------------------------------------------------------------------

    /** The PIN was entered: the vault list and settings of [scope] may open */
    fun openPrivateArea(scope: VaultScope) {
        synchronized(lock) { privateArea = PrivateArea(scope, clock()) }
        changed()
    }

    fun isPrivateAreaOpen(scope: VaultScope?): Boolean = scope != null && synchronized(lock) { privateArea?.scope == scope }

    fun touchPrivateArea() {
        synchronized(lock) { privateArea?.lastActivity = clock() }
    }

    fun closePrivateArea(reason: VaultLockReason = VaultLockReason.LEFT_VAULT) {
        val closed = synchronized(lock) { privateArea.also { privateArea = null } } ?: return
        _lockEvents.tryEmit(VaultLockEvent(closed.scope, null, reason))
        changed()
    }

    // ---------------------------------------------------------------------------------------
    // Vaults
    // ---------------------------------------------------------------------------------------

    fun unlock(
        scope: VaultScope,
        vaultId: String,
    ) {
        val now = clock()
        synchronized(lock) { unlocks[scope to vaultId] = Unlock(now, now) }
        changed()
    }

    /** Marks the vault's screens as on screen. Pair with [leave]. */
    fun enter(
        scope: VaultScope,
        vaultId: String,
    ) {
        synchronized(lock) {
            val unlock = unlocks[scope to vaultId] ?: return
            unlock.entered++
            unlock.lastActivity = clock()
        }
        changed()
    }

    fun leave(
        scope: VaultScope,
        vaultId: String,
    ) {
        val lockNow =
            synchronized(lock) {
                val unlock = unlocks[scope to vaultId] ?: return
                if (unlock.entered > 0) unlock.entered--
                unlock.lastActivity = clock()
                unlock.entered == 0 && settingsFor(scope).lockOnLeave
            }
        if (lockNow) lock(scope, vaultId, VaultLockReason.LEFT_VAULT) else changed()
    }

    /** Anything done inside the vault keeps it open */
    fun touch(
        scope: VaultScope,
        vaultId: String,
    ) {
        synchronized(lock) {
            unlocks[scope to vaultId]?.lastActivity = clock()
            if (privateArea?.scope == scope) privateArea?.lastActivity = clock()
        }
    }

    fun lock(
        scope: VaultScope,
        vaultId: String,
        reason: VaultLockReason,
    ) {
        val removed = synchronized(lock) { unlocks.remove(scope to vaultId) } ?: return
        _lockEvents.tryEmit(VaultLockEvent(scope, vaultId, reason))
        changed()
    }

    fun lockAll(reason: VaultLockReason) {
        val keys = synchronized(lock) { unlocks.keys.toList() }
        keys.forEach { (scope, vaultId) -> lock(scope, vaultId, reason) }
        closePrivateArea(reason)
    }

    /** Locks everything that isn't [active]'s: sign out, user switch and server switch land here */
    fun onActiveScopeChanged(active: VaultScope?) {
        val keys = synchronized(lock) { unlocks.keys.filter { it.first != active } }
        keys.forEach { (scope, vaultId) -> lock(scope, vaultId, VaultLockReason.ACCOUNT_CHANGED) }
        if (synchronized(lock) { privateArea?.scope != null && privateArea?.scope != active }) {
            closePrivateArea(VaultLockReason.ACCOUNT_CHANGED)
        }
    }

    /** Applies the inactivity timeout; runs on a timer, public so tests can drive it */
    fun checkTimeouts() {
        val now = clock()
        val expired =
            synchronized(lock) {
                unlocks.entries
                    .filter { now - it.value.lastActivity >= settingsFor(it.key.first).autoLockMillis }
                    .map { it.key }
            }
        expired.forEach { (scope, vaultId) -> lock(scope, vaultId, VaultLockReason.TIMEOUT) }
        val areaExpired =
            synchronized(lock) {
                val area = privateArea
                area != null && now - area.lastActivity >= settingsFor(area.scope).autoLockMillis
            }
        if (areaExpired) closePrivateArea(VaultLockReason.TIMEOUT)
    }

    /** Checks the timeout every [intervalMs] for as long as [scope] lives */
    fun runTicker(
        scope: CoroutineScope,
        intervalMs: Long = TICK_MS,
    ): Job =
        scope.launch {
            while (isActive) {
                delay(intervalMs)
                if (hasUnlocked) checkTimeouts()
            }
        }

    companion object {
        const val TICK_MS = 15_000L
    }
}
