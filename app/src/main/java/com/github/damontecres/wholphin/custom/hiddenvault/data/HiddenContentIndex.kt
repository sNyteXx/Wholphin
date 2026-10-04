package com.github.damontecres.wholphin.custom.hiddenvault.data

import com.github.damontecres.wholphin.custom.hiddenvault.model.HiddenTagPolicy
import com.github.damontecres.wholphin.custom.hiddenvault.model.ItemIds
import com.github.damontecres.wholphin.custom.hiddenvault.model.LibraryRule
import com.github.damontecres.wholphin.custom.hiddenvault.model.TagMatch
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.ItemRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a hidden item came from
 */
data class HiddenEntry(
    val vaultId: String,
    val libraryId: String,
)

/**
 * Every item the server holds that the current rules hide, keyed by normalized id.
 *
 * Built from one tag query per configured library, so an item is only in it when it really sits
 * in that library. Episodes and seasons are usually not listed; they are hidden through their
 * series. Lookups are plain hash map reads.
 */
class HiddenContentIndex(
    val fingerprint: String,
    val builtAtMs: Long,
    entries: Map<String, HiddenEntry>,
    /** Every library id the user can see, when known. Lets paging skip libraries without rules. */
    val knownLibraryIds: Set<String> = emptySet(),
) {
    private val entries: Map<String, HiddenEntry> = entries.toMap()

    operator fun get(id: String?): HiddenEntry? = if (id == null) null else entries[id]

    fun contains(id: String?): Boolean = get(id) != null

    val size: Int get() = entries.size

    val ids: Set<String> get() = entries.keys

    /** The ids hidden into [vaultId], optionally only those from [libraryId] */
    fun idsOf(
        vaultId: String? = null,
        libraryId: String? = null,
    ): List<String> =
        entries.entries
            .filter { (vaultId == null || it.value.vaultId == vaultId) && (libraryId == null || it.value.libraryId == libraryId) }
            .map { it.key }

    fun isStale(
        nowMs: Long,
        maxAgeMs: Long,
    ): Boolean = nowMs - builtAtMs > maxAgeMs

    /** Grouped per library so the stored form doesn't repeat the vault and library ids per item */
    fun encode(): String {
        val groups =
            entries.entries
                .groupBy({ "${it.value.vaultId}|${it.value.libraryId}" }, { it.key })
        return json.encodeToString(
            Stored.serializer(),
            Stored(1, fingerprint, builtAtMs, groups, knownLibraryIds.toList()),
        )
    }

    @Serializable
    private data class Stored(
        val v: Int,
        val fp: String,
        val builtAt: Long,
        val groups: Map<String, List<String>>,
        val libraries: List<String> = emptyList(),
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(source: String?): HiddenContentIndex? {
            if (source.isNullOrBlank()) return null
            return try {
                val stored = json.decodeFromString(Stored.serializer(), source)
                if (stored.v != 1) return null
                val entries = mutableMapOf<String, HiddenEntry>()
                stored.groups.forEach { (key, ids) ->
                    val parts = key.split('|')
                    if (parts.size != 2) return@forEach
                    val entry = HiddenEntry(parts[0], parts[1])
                    ids.forEach { id -> ItemIds.normalize(id)?.let { entries[it] = entry } }
                }
                HiddenContentIndex(stored.fp, stored.builtAt, entries, stored.libraries.toSet())
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * One page of a library's tagged items as the server answered it
 */
data class IndexPage(
    val items: List<ItemRef>,
    val totalRecordCount: Int?,
)

/**
 * The server queries an index build needs. Implemented over the unfiltered [org.jellyfin.sdk.api.client.ApiClient]
 * in the app and by a fake in tests.
 */
interface HiddenIndexSource {
    /**
     * Items below [libraryId] (recursive) carrying any of [tags], with their `Tags` field
     */
    suspend fun taggedItems(
        libraryId: String,
        tags: List<String>,
        startIndex: Int,
        limit: Int,
    ): IndexPage

    /** Ids of every library the user can see, or null when unknown */
    suspend fun libraryIds(): Set<String>?
}

/**
 * Counts what a build cost, for the log and the tests
 */
data class IndexBuildStats(
    var requests: Int = 0,
    var itemsSeen: Int = 0,
    var itemsRejected: Int = 0,
)

/**
 * Builds a [HiddenContentIndex] with one tag query per configured library.
 *
 * Jellyfin reads several `Tags` as "any of them", so one query per library covers all of its
 * tags. The server compares a cleaned form of each tag (punctuation dropped), which is looser
 * than exact matching, so every result is checked against its own tags here and anything the
 * server matched too generously is dropped.
 */
class HiddenIndexBuilder(
    private val source: HiddenIndexSource,
) {
    /**
     * @param previous the index of the same rules built earlier: a library whose query fails this
     * time keeps its earlier entries instead of failing the whole build
     */
    suspend fun build(
        policy: HiddenTagPolicy,
        nowMs: Long,
        stats: IndexBuildStats = IndexBuildStats(),
        previous: HiddenContentIndex? = null,
    ): HiddenContentIndex =
        coroutineScope {
            val semaphore = Semaphore(CONCURRENCY)
            val libraries =
                async {
                    try {
                        source.libraryIds()?.mapNotNull { ItemIds.normalize(it) }?.toSet()
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (_: Exception) {
                        // Only an optimisation for paging, never needed for correctness
                        null
                    }
                }
            val earlier = previous?.takeIf { it.fingerprint == policy.fingerprint }
            val perLibrary =
                policy.rules.map { rule ->
                    async {
                        semaphore.withPermit {
                            try {
                                collect(rule, stats)
                            } catch (ex: CancellationException) {
                                throw ex
                            } catch (ex: Exception) {
                                // Without an earlier answer for this library the build fails,
                                // which keeps the cautious tag only fallback in place
                                earlier ?: throw ex
                                earlier.idsOf(libraryId = rule.libraryId).associateWith { HiddenEntry(rule.vaultId, rule.libraryId) }
                            }
                        }
                    }
                }
            val entries = mutableMapOf<String, HiddenEntry>()
            perLibrary.forEach { entries.putAll(it.await()) }
            HiddenContentIndex(policy.fingerprint, nowMs, entries, libraries.await().orEmpty())
        }

    private suspend fun collect(
        rule: LibraryRule,
        stats: IndexBuildStats,
    ): Map<String, HiddenEntry> {
        val result = mutableMapOf<String, HiddenEntry>()
        val tags = rule.tags.sorted()
        val entry = HiddenEntry(rule.vaultId, rule.libraryId)
        var start = 0
        for (page in 0 until MAX_PAGES) {
            val response = source.taggedItems(rule.libraryId, tags, start, PAGE_SIZE)
            synchronized(stats) { stats.requests++ }
            response.items.forEach { item ->
                val id = item.id ?: return@forEach
                synchronized(stats) { stats.itemsSeen++ }
                val itemTags = item.tags
                // A server that ignored the field leaves nothing to check against. It already
                // filtered on the tag, so keeping the item is the answer that can't leak.
                if (itemTags != null && !TagMatch.matchesAny(itemTags, rule.tags)) {
                    synchronized(stats) { stats.itemsRejected++ }
                    return@forEach
                }
                result[id] = entry
            }
            start += response.items.size
            val total = response.totalRecordCount
            if (response.items.size < PAGE_SIZE) break
            if (total != null && start >= total) break
        }
        return result
    }

    companion object {
        const val PAGE_SIZE = 1000

        /** Only stops a misbehaving server from paging forever */
        const val MAX_PAGES = 50

        const val CONCURRENCY = 3
    }
}
