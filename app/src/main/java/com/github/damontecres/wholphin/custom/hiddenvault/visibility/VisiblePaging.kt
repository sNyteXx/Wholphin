package com.github.damontecres.wholphin.custom.hiddenvault.visibility

import com.github.damontecres.wholphin.custom.hiddenvault.data.HiddenContentService
import com.github.damontecres.wholphin.custom.hiddenvault.model.ItemIds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult

/**
 * What one paged query is about, as far as visibility cares
 */
data class PagedQueryInfo(
    /** The item the list is about (series of an episode list, parent of a folder list), if any */
    val anchorId: String? = null,
    /** The query explicitly names item ids: a global list, never an item specific one */
    val namesIds: Boolean = false,
    /** Server order changes between requests (random sort): positions mean nothing */
    val unstableOrder: Boolean = false,
)

/**
 * One page of visible items
 */
data class VisiblePage(
    val items: List<BaseItemDto>,
    val totalCount: Int?,
)

/**
 * Exact paging over a server list with the hidden items taken out, for one
 * [com.github.damontecres.wholphin.util.ApiRequestPager].
 *
 * The pager addresses items by position: page N holds positions `N * pageSize` up to the next
 * page, and the list size is the total count. A page that came back short would leave holes, and
 * positions behind it would be off. So positions here are *visible* positions:
 *
 * * A list that fits into its first request is answered by that request alone.
 * * Otherwise one lightweight "skeleton" of the same query (ids, parent ids and tags only) tells
 *   where every visible item sits on the server. Each page is then exactly one request for the
 *   exact server range, the total is exact, and jumping to any position (letters, a restored
 *   scroll position) works without walking the pages in between.
 * * Lists that can't contain hidden content (a library without rules) are never touched.
 *
 * Every page is filtered by the same rules as everything else, so the skeleton only ever decides
 * positions, never visibility.
 */
class VisiblePaging<T>(
    private val client: HiddenContentApiClient,
    private val info: PagedQueryInfo,
    private val prepare: (startIndex: Int, limit: Int, includeTotal: Boolean) -> T,
    private val execute: suspend (ApiClient, T) -> BaseItemDtoQueryResult,
    /**
     * Set for the vault's own lists: keeps exactly the items this returns true for instead of
     * applying the normal rules
     */
    private val vaultSelector: ((ItemRef) -> Boolean)? = null,
) {
    private val mutex = Mutex()
    private var skeleton: Skeleton? = null

    /** Requests this pager sent, for the tests */
    var requestCount = 0
        private set

    private val withTags by lazy { QueryRewritingApiClient.withTags(client.raw) }
    private val skeletonClient by lazy { QueryRewritingApiClient.skeleton(client.raw) }

    /**
     * The visible page [pageNumber], or null when the vault has nothing to say about this list
     * and the caller should load it the normal way.
     */
    suspend fun fetchPage(
        pageNumber: Int,
        pageSize: Int,
        includeTotalCount: Boolean,
    ): VisiblePage? =
        mutex.withLock {
            val runtime = client.runtime ?: return@withLock null
            val service = runtime.activeService() ?: return@withLock null
            if (vaultSelector == null && cannotHoldHidden(service)) return@withLock null
            val kind = if (info.anchorId != null && !info.namesIds) AllowanceKind.CHILDREN else AllowanceKind.NONE
            val allowance = VaultAllowance.of(kind, info.anchorId, service, runtime)
            val generation = service.generation.value
            if (includeTotalCount || skeleton?.generation != generation) skeleton = null

            if (info.unstableOrder) return@withLock unstablePage(pageSize, service, allowance)

            var current = skeleton
            if (current == null && pageNumber == 0) {
                // The cheap path: maybe the whole list fits into one request
                val window = pageSize + HEADROOM
                val first = run(withTags, prepare(0, window, true))
                val keptFirst = keep(first.items, service, allowance)
                val total = first.totalRecordCount
                if (first.items.size < window || total in 1..first.items.size) {
                    skeleton = Skeleton.of(generation, first.items, keptFirst, first.items.size)
                    return@withLock VisiblePage(keptFirst.take(pageSize), keptFirst.size)
                }
                current = buildSkeleton(generation, service, allowance)
                val wanted = current.visibleIds(0, pageSize)
                if (keptFirst.size >= wanted.size && keptFirst.take(wanted.size).map { it.normalizedId() } == wanted) {
                    return@withLock VisiblePage(keptFirst.take(wanted.size), current.visibleTotal)
                }
            }
            if (current == null) current = buildSkeleton(generation, service, allowance)
            exactPage(current, pageNumber, pageSize, service, allowance, retry = true)
        }

    private fun cannotHoldHidden(service: HiddenContentService): Boolean {
        val anchor = info.anchorId ?: return false
        val index = service.index ?: return false
        // A library without rules can't hold hidden items
        return service.policy.ruleFor(anchor) == null && index.knownLibraryIds.contains(anchor)
    }

    private suspend fun exactPage(
        skeleton: Skeleton,
        pageNumber: Int,
        pageSize: Int,
        service: HiddenContentService,
        allowance: VaultAllowance,
        retry: Boolean,
    ): VisiblePage {
        val visibleStart = pageNumber * pageSize
        if (visibleStart >= skeleton.visibleTotal) return VisiblePage(emptyList(), skeleton.visibleTotal)
        val visibleEnd = minOf(visibleStart + pageSize, skeleton.visibleTotal)
        val range = skeleton.rawRange(visibleStart, visibleEnd)
        val page = run(withTags, prepare(range.first, range.last - range.first + 1, false))
        val kept = keep(page.items, service, allowance)
        val expected = skeleton.visibleIds(visibleStart, visibleEnd)
        if (retry && expected.isNotEmpty() && kept.map { it.normalizedId() } != expected) {
            // The list changed on the server since the skeleton was read
            val rebuilt = buildSkeleton(skeleton.generation, service, allowance)
            this.skeleton = rebuilt
            return exactPage(rebuilt, pageNumber, pageSize, service, allowance, retry = false)
        }
        return VisiblePage(kept.take(pageSize), skeleton.visibleTotal)
    }

    /** Random order: just make the page full, positions are meaningless anyway */
    private suspend fun unstablePage(
        pageSize: Int,
        service: HiddenContentService,
        allowance: VaultAllowance,
    ): VisiblePage {
        val limit = (pageSize * 2).coerceAtMost(maxOf(pageSize, HiddenContentApiClient.MAX_READ_AHEAD_PAGE))
        val result = run(withTags, prepare(0, limit, true))
        val kept = keep(result.items, service, allowance)
        val hidden = result.items.size - kept.size
        return VisiblePage(kept.take(pageSize), (result.totalRecordCount - hidden).coerceAtLeast(kept.size.coerceAtMost(pageSize)))
    }

    private suspend fun buildSkeleton(
        generation: Int,
        service: HiddenContentService,
        allowance: VaultAllowance,
    ): Skeleton {
        val rawItems = mutableListOf<BaseItemDto>()
        var total = 0
        while (rawItems.size < SKELETON_CAP) {
            val chunk = run(skeletonClient, prepare(rawItems.size, SKELETON_CHUNK, true))
            total = chunk.totalRecordCount
            rawItems.addAll(chunk.items)
            if (chunk.items.size < SKELETON_CHUNK || (total > 0 && rawItems.size >= total)) break
        }
        val kept = keep(rawItems, service, allowance)
        val built = Skeleton.of(generation, rawItems, kept, maxOf(total, rawItems.size))
        skeleton = built
        return built
    }

    private suspend fun run(
        api: ApiClient,
        request: T,
    ): BaseItemDtoQueryResult {
        requestCount++
        return execute(api, request)
    }

    private suspend fun keep(
        items: List<BaseItemDto>,
        service: HiddenContentService,
        allowance: VaultAllowance,
    ): List<BaseItemDto> {
        if (items.isEmpty()) return items
        val refs = items.map { ItemRef.of(it) }
        vaultSelector?.let { selector -> return items.filterIndexed { i, _ -> selector(refs[i]) } }
        val verdicts = service.settle(refs)
        return items.filterIndexed { i, _ ->
            when (val verdict = verdicts[i]) {
                Verdict.Visible -> true
                is Verdict.Hidden -> allowance.allows(refs[i], verdict.vaultId)
            }
        }
    }

    /**
     * Where the visible items of one query sit on the server
     */
    private class Skeleton(
        val generation: Int,
        private val rawIds: List<String?>,
        /** Server position of each visible item, in order */
        private val visiblePositions: IntArray,
        /** Server items past the scanned part, assumed visible */
        private val unscanned: Int,
    ) {
        val visibleTotal: Int get() = visiblePositions.size + unscanned

        fun rawPosition(visible: Int): Int =
            if (visible < visiblePositions.size) {
                visiblePositions[visible]
            } else {
                rawIds.size + (visible - visiblePositions.size)
            }

        fun rawRange(
            visibleStart: Int,
            visibleEndExclusive: Int,
        ): IntRange = rawPosition(visibleStart)..rawPosition(visibleEndExclusive - 1)

        fun visibleIds(
            visibleStart: Int,
            visibleEndExclusive: Int,
        ): List<String?> = (visibleStart until minOf(visibleEndExclusive, visiblePositions.size)).map { rawIds[visiblePositions[it]] }

        companion object {
            fun of(
                generation: Int,
                raw: List<BaseItemDto>,
                kept: List<BaseItemDto>,
                rawTotal: Int,
            ): Skeleton {
                val keptIds = kept.mapTo(HashSet()) { it.normalizedId() }
                val positions = raw.indices.filter { raw[it].normalizedId() in keptIds }.toIntArray()
                return Skeleton(
                    generation,
                    raw.map { it.normalizedId() },
                    positions,
                    (rawTotal - raw.size).coerceAtLeast(0),
                )
            }
        }
    }

    companion object {
        /** Extra items asked for with the first page, so a few hidden ones don't cost a request */
        const val HEADROOM = 20

        const val SKELETON_CHUNK = 5_000

        /** Past this many server items positions are assumed to hold no hidden content */
        const val SKELETON_CAP = 20_000

        private fun BaseItemDto.normalizedId(): String? = ItemIds.of(id)
    }
}
