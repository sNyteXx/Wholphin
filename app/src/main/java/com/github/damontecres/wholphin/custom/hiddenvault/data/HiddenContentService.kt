package com.github.damontecres.wholphin.custom.hiddenvault.data

import com.github.damontecres.wholphin.custom.hiddenvault.model.HiddenTagPolicy
import com.github.damontecres.wholphin.custom.hiddenvault.model.HiddenVaultConfig
import com.github.damontecres.wholphin.custom.hiddenvault.model.ItemIds
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.ItemRef
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * The hidden content rules of one user on one server, and every answer derived from them.
 *
 * Owns the config, the hidden index and the memory of tag matches that turned out to sit outside
 * every vault library. All checks ([verdict]) are synchronous map lookups; the only network it
 * ever does is the index build (one request per configured library), the config sync, and a
 * rebuild when an item turns up whose own tags match but which the index doesn't know yet.
 */
class HiddenContentService(
    val scope: VaultScope,
    private val store: VaultKeyValueStore,
    private val indexSource: HiddenIndexSource,
    private val remote: VaultConfigRemote?,
    private val backgroundScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: VaultLog = VaultLog.NONE,
) {
    @Volatile
    var config: HiddenVaultConfig = HiddenVaultConfig.EMPTY
        private set

    @Volatile
    var policy: HiddenTagPolicy = HiddenTagPolicy.NONE
        private set

    @Volatile
    var index: HiddenContentIndex? = null
        private set

    @Volatile
    var deviceSettings: VaultDeviceSettings = VaultDeviceSettings()
        private set

    private val outOfScope: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val suspects = ConcurrentHashMap<String, Long>()

    private val lock = Any()
    private var refreshing: Deferred<Unit>? = null
    private var refreshingFingerprint: String? = null
    private var refreshStartedAt: Long = 0L
    private var initialSync: Deferred<Unit>? = null
    private var delayedRefresh: Job? = null

    private val _generation = MutableStateFlow(0)

    /** Changes whenever the config or the index does; screens reload on it */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /** Ids that joined the hidden set on the last index change */
    @Volatile
    var lastAddedIds: Set<String> = emptySet()
        private set

    /** Counters for the tests and the log */
    @Volatile
    var indexBuilds = 0
        private set

    @Volatile
    var indexRequests = 0
        private set

    val isActive: Boolean get() = policy.isActive

    /** The current rules' fingerprint, for cache keys */
    val fingerprint: String get() = policy.fingerprint

    init {
        loadPersisted()
    }

    private fun loadPersisted() {
        deviceSettings = VaultDeviceSettings.decode(store.get(VaultStorageKeys.device(scope)))
        config = HiddenVaultConfig.decode(store.get(VaultStorageKeys.config(scope)))
        policy = HiddenTagPolicy.from(config)
        if (!policy.isActive) return
        val stored = HiddenContentIndex.decode(store.get(VaultStorageKeys.index(scope)))
        if (stored != null && stored.fingerprint == policy.fingerprint) {
            index = stored
            loadOutOfScope()
        }
    }

    private fun loadOutOfScope() {
        val raw = store.get(VaultStorageKeys.outOfScope(scope)) ?: return
        try {
            val stored = json.decodeFromString(OUT_OF_SCOPE_SERIALIZER, raw)
            if (stored.firstOrNull() == policy.fingerprint) {
                outOfScope.addAll(stored.drop(1))
            }
        } catch (_: Exception) {
            // Recomputed on the next rebuild
        }
    }

    private fun persistOutOfScope() {
        try {
            store.put(
                VaultStorageKeys.outOfScope(scope),
                json.encodeToString(OUT_OF_SCOPE_SERIALIZER, listOf(policy.fingerprint) + outOfScope.sorted()),
            )
        } catch (ex: Exception) {
            log.w("could not persist out of scope ids", ex)
        }
    }

    private fun bumpGeneration() {
        _generation.value = _generation.value + 1
    }

    // ---------------------------------------------------------------------------------------
    // Config & sync
    // ---------------------------------------------------------------------------------------

    /**
     * Stores [next] and, when what is hidden changed, rebuilds the index before returning so
     * nothing renders against the old rules. With sync on, the server copy follows.
     */
    suspend fun saveConfig(next: HiddenVaultConfig) {
        val saved = next.normalized().copy(updatedAt = clock())
        applyConfig(saved)
        if (deviceSettings.syncEnabled) backgroundScope.launch { push(saved) }
    }

    private suspend fun push(config: HiddenVaultConfig) {
        val remote = remote ?: return
        try {
            remote.push(config)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            // Picked up again by the next sync, since the local copy is newer
            log.w("config push failed", ex)
        }
    }

    suspend fun saveDeviceSettings(next: VaultDeviceSettings) {
        val enablingSync = next.syncEnabled && !deviceSettings.syncEnabled
        deviceSettings = next
        store.put(VaultStorageKeys.device(scope), next.encode())
        if (enablingSync) {
            try {
                syncNow()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                log.w("sync after enabling failed", ex)
            }
        }
        bumpGeneration()
    }

    /**
     * Fetches the server copy once per session. The first lists wait for it at most
     * [SYNC_WAIT_MS], so a rule added on another device can't slip through on this one; a later
     * answer still applies the moment it arrives.
     */
    suspend fun ensureSynced() {
        if (remote == null || !deviceSettings.syncEnabled) return
        val running =
            synchronized(lock) {
                initialSync
                    ?: backgroundScope
                        .async {
                            try {
                                syncNow()
                            } catch (ex: CancellationException) {
                                throw ex
                            } catch (ex: Exception) {
                                log.w("initial sync failed", ex)
                            }
                            Unit
                        }.also { initialSync = it }
            }
        withTimeoutOrNull(SYNC_WAIT_MS) { running.await() }
    }

    /**
     * Takes the server copy when it is newer and puts this device's copy there when the server's
     * is older or missing. True when the server copy was applied.
     */
    suspend fun syncNow(): Boolean {
        val remote = remote ?: return false
        if (!deviceSettings.syncEnabled) return false
        val remoteConfig = remote.pull()
        if (remoteConfig != null && remoteConfig.updatedAt > config.updatedAt) {
            applyConfig(remoteConfig.normalized())
            return true
        }
        val localIsNewer =
            if (remoteConfig == null) config.updatedAt > 0 else config.updatedAt > remoteConfig.updatedAt
        if (localIsNewer) push(config)
        return false
    }

    private suspend fun applyConfig(next: HiddenVaultConfig) {
        val rulesChanged = next.fingerprint != config.fingerprint
        config = next
        store.put(VaultStorageKeys.config(scope), next.encode())
        if (!rulesChanged) {
            bumpGeneration()
            return
        }
        synchronized(lock) {
            policy = HiddenTagPolicy.from(next)
            index = null
            outOfScope.clear()
            suspects.clear()
        }
        store.remove(VaultStorageKeys.index(scope))
        store.remove(VaultStorageKeys.outOfScope(scope))
        bumpGeneration()
        if (policy.isActive) {
            try {
                refreshIndex()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                log.w("index build after config change failed, tag only fallback", ex)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Index lifecycle
    // ---------------------------------------------------------------------------------------

    /**
     * Makes sure there is an index to answer from. A stored one is used at once and refreshed in
     * the background when old; only the very first build is waited for. If even that fails,
     * answers fall back to the tags alone, which hides too much rather than too little.
     */
    suspend fun ensureReady() {
        ensureSynced()
        if (!isActive) return
        val current = index
        if (current != null) {
            if (current.isStale(clock(), STALE_AFTER_MS)) refreshInBackground()
            return
        }
        try {
            withTimeoutOrNull(FIRST_BUILD_TIMEOUT_MS) { refreshIndex() }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            log.w("index build failed, tag only fallback", ex)
        }
    }

    /** Starts a rebuild unless one is running, without waiting for it */
    fun refreshInBackground() {
        if (!isActive) return
        startRefresh()
    }

    /** Rebuilds the index. Concurrent callers share one build. */
    suspend fun refreshIndex() {
        if (!isActive) return
        startRefresh().await()
    }

    private fun startRefresh(): Deferred<Unit> =
        synchronized(lock) {
            // A build for older rules returns without an index; never join it
            refreshing?.takeIf { it.isActive && refreshingFingerprint == policy.fingerprint }
                ?: run {
                    val startedAt = clock()
                    refreshStartedAt = startedAt
                    refreshingFingerprint = policy.fingerprint
                    val deferred = CompletableDeferred<Unit>()
                    refreshing = deferred
                    backgroundScope.launch {
                        try {
                            rebuild(startedAt)
                            deferred.complete(Unit)
                        } catch (ex: Throwable) {
                            deferred.completeExceptionally(ex)
                            if (ex is CancellationException) throw ex
                        } finally {
                            synchronized(lock) { if (refreshing === deferred) refreshing = null }
                        }
                    }
                    deferred
                }
        }

    private suspend fun rebuild(startedAt: Long) {
        val policy = this.policy
        if (!policy.isActive) return
        val stats = IndexBuildStats()
        val built = HiddenIndexBuilder(indexSource).build(policy, startedAt, stats)
        indexBuilds++
        indexRequests += stats.requests
        log.d("index built: ${built.size} hidden, $stats")
        // The rules changed while this ran; that change starts a build of its own
        if (policy.fingerprint != this.policy.fingerprint) return

        val previous = index
        lastAddedIds = built.ids.filterTo(mutableSetOf()) { previous?.contains(it) != true }
        synchronized(lock) {
            index = built
            // A suspect seen before this build started has been looked for by it. If the build
            // didn't place it, it sits outside every covered library.
            suspects.entries.toList().forEach { (id, firstSeen) ->
                if (firstSeen <= startedAt) {
                    suspects.remove(id)
                    if (!built.contains(id)) outOfScope.add(id)
                }
            }
        }
        try {
            store.put(VaultStorageKeys.index(scope), built.encode())
        } catch (ex: Exception) {
            log.w("could not persist index", ex)
        }
        persistOutOfScope()
        bumpGeneration()
    }

    // ---------------------------------------------------------------------------------------
    // Verdicts
    // ---------------------------------------------------------------------------------------

    private fun entryFor(ref: ItemRef): HiddenEntry? {
        val index = index ?: return null
        return index[ref.id]
            ?: index[ref.seriesId]
            ?: index[ref.seasonId]
            ?: index[ref.albumId]
            ?: index[ref.parentId]
    }

    /** The answer from what is in hand, without any network */
    fun verdict(ref: ItemRef): Verdict {
        if (!policy.isActive) return Verdict.Visible
        entryFor(ref)?.let { return Verdict.Hidden(it.vaultId) }
        val tags = ref.tags
        if (tags != null && policy.matchesAnyRule(tags)) {
            // Containers sit in no library, so their own tags are the whole answer
            if (ref.type in CONTAINER_TYPES) return Verdict.Hidden(null)
            if (index == null) return Verdict.Hidden(null)
            val id = ref.id
            if (id != null && id !in outOfScope) return Verdict.Hidden(null, suspectId = id)
        }
        return Verdict.Visible
    }

    /** The vault [ref] belongs to according to the index, if any */
    fun vaultOf(ref: ItemRef): String? = entryFor(ref)?.vaultId

    /** Whether [ref] belongs to [vaultId]: in its index, or a child of something that is */
    fun belongsToVault(
        ref: ItemRef,
        vaultId: String,
    ): Boolean = vaultOf(ref) == vaultId

    fun vaultOfId(id: String?): String? = index?.get(ItemIds.normalize(id))?.vaultId

    /**
     * The final verdict for each of [refs]: suspects wait (bounded) for an index rebuild that can
     * place them, and stay hidden when it can't finish in time.
     */
    suspend fun settle(refs: List<ItemRef>): List<Verdict> {
        if (!isActive || refs.isEmpty()) return refs.map { Verdict.Visible }
        val first = refs.map { verdict(it) }
        val suspectIds = first.mapNotNullTo(mutableSetOf()) { (it as? Verdict.Hidden)?.suspectId }
        if (suspectIds.isEmpty()) return first
        placeSuspects(suspectIds)
        return refs.mapIndexed { i, ref -> if ((first[i] as? Verdict.Hidden)?.suspectId != null) verdict(ref) else first[i] }
    }

    private suspend fun placeSuspects(ids: Set<String>) {
        val now = clock()
        ids.forEach { suspects.putIfAbsent(it, now) }
        val placed =
            withTimeoutOrNull(SUSPECT_REFRESH_TIMEOUT_MS) {
                try {
                    val running =
                        synchronized(lock) {
                            refreshing?.takeIf { it.isActive && refreshingFingerprint == policy.fingerprint }
                        }
                    if (running != null) {
                        running.await()
                        // That build may have started before these were seen; one more settles
                        // them for good
                        if (ids.any { suspects.containsKey(it) }) refreshIndex()
                    } else {
                        refreshIndex()
                    }
                    true
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    log.w("could not place suspects yet", ex)
                    false
                }
            }
        if (placed != true) {
            synchronized(lock) {
                if (delayedRefresh?.isActive != true) {
                    delayedRefresh =
                        backgroundScope.launch {
                            delay(RETRY_REFRESH_DELAY_MS)
                            refreshInBackground()
                        }
                }
            }
        }
    }

    companion object {
        /** How old the index may get before it's rebuilt in the background */
        const val STALE_AFTER_MS = 10 * 60_000L

        /** How long the first lists of a session wait for another device's config */
        const val SYNC_WAIT_MS = 2_000L

        const val FIRST_BUILD_TIMEOUT_MS = 20_000L
        const val SUSPECT_REFRESH_TIMEOUT_MS = 6_000L
        const val RETRY_REFRESH_DELAY_MS = 20_000L

        /** Containers sit in no library, so their own tags are the whole answer */
        val CONTAINER_TYPES = setOf("BoxSet", "Playlist")

        private val json = Json { ignoreUnknownKeys = true }
        private val OUT_OF_SCOPE_SERIALIZER = ListSerializer(String.serializer())
    }
}
