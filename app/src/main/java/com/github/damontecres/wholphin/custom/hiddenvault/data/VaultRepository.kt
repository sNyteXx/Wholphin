package com.github.damontecres.wholphin.custom.hiddenvault.data

import com.github.damontecres.wholphin.custom.hiddenvault.model.ItemIds
import com.github.damontecres.wholphin.custom.hiddenvault.model.TagMatch
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultDefinition
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultLibrary
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.ItemRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest

/**
 * One page of a vault library
 */
data class VaultPage(
    val items: List<BaseItemDto>,
    /** How many server items this page consumed, for the next start index */
    val rawCount: Int,
    val totalCount: Int?,
)

/**
 * The vault context: reads only the hidden content of one vault.
 *
 * Library lists ask the server for the vault's tags directly (`ParentId=<library>&Tags=…`), so
 * thousands of ordinary items never travel just to be thrown away, and every result is checked
 * exactly against its own tags before it is shown. Lists that can't filter by tag (continue
 * watching, next up, episode search) keep only what the hidden index places in this vault.
 *
 * Lives as long as the vault's screens do; nothing here is cached beyond it or written to disk.
 */
class VaultRepository(
    private val service: HiddenContentService,
    val vault: VaultDefinition,
    private val rawApi: ApiClient,
) {
    private val memoLock = Mutex()
    private val memo = mutableMapOf<String, Deferred<List<BaseItemDto>>>()

    /** Requests sent, for the tests */
    @Volatile
    var requestCount = 0
        private set

    val libraries: List<VaultLibrary> get() = vault.libraries.filter { it.hasTags }

    private val hideWatched: Boolean get() = service.config.settings.hideWatched

    /** Exactly hidden in [library] by its own tags */
    fun taggedIn(
        ref: ItemRef,
        library: VaultLibrary,
    ): Boolean {
        val tags = ref.tags ?: return service.belongsToVault(ref, vault.id)
        return TagMatch.matchesAny(tags, library.normalizedTags)
    }

    fun inVault(ref: ItemRef): Boolean = service.belongsToVault(ref, vault.id)

    /** The server query for one page of [library], tag filtered on the server */
    fun libraryRequest(
        library: VaultLibrary,
        startIndex: Int = 0,
        limit: Int = PAGE_SIZE,
        sortBy: List<ItemSortBy> = listOf(ItemSortBy.SORT_NAME),
        sortOrder: List<SortOrder> = listOf(SortOrder.ASCENDING),
    ): GetItemsRequest =
        GetItemsRequest(
            parentId = ItemIds.toUuid(library.libraryId),
            recursive = true,
            tags = library.normalizedTags.sorted(),
            includeItemTypes = typesFor(library),
            filters = if (hideWatched) listOf(ItemFilter.IS_UNPLAYED) else null,
            sortBy = sortBy,
            sortOrder = sortOrder,
            startIndex = startIndex,
            limit = limit,
            fields = FIELDS,
            enableImageTypes = IMAGE_TYPES,
            imageTypeLimit = 1,
            enableTotalRecordCount = true,
        )

    suspend fun libraryPage(
        library: VaultLibrary,
        startIndex: Int = 0,
        limit: Int = PAGE_SIZE,
        sortBy: List<ItemSortBy> = listOf(ItemSortBy.SORT_NAME),
        sortOrder: List<SortOrder> = listOf(SortOrder.ASCENDING),
    ): VaultPage {
        requestCount++
        val result =
            rawApi.itemsApi
                .getItems(libraryRequest(library, startIndex, limit, sortBy, sortOrder))
                .content
        return VaultPage(
            result.items.filter { taggedIn(ItemRef.of(it), library) },
            result.items.size,
            result.totalRecordCount,
        )
    }

    /** The first row of a vault library on the vault's home */
    suspend fun libraryRow(library: VaultLibrary): List<BaseItemDto> =
        memoized("row:${library.libraryId}:$hideWatched") { libraryPage(library, limit = ROW_LIMIT).items }

    suspend fun recentlyAdded(): List<BaseItemDto> =
        memoized("recent:$hideWatched") {
            coroutineScope {
                libraries
                    .map { library ->
                        async {
                            libraryPage(
                                library,
                                limit = ROW_LIMIT,
                                sortBy =
                                    if (library.collectionType == TV_SHOWS) {
                                        listOf(ItemSortBy.DATE_LAST_CONTENT_ADDED, ItemSortBy.DATE_CREATED)
                                    } else {
                                        listOf(ItemSortBy.DATE_CREATED)
                                    },
                                sortOrder = listOf(SortOrder.DESCENDING),
                            ).items
                        }
                    }.awaitAll()
                    .flatten()
                    .sortedByDescending { (it.dateLastMediaAdded ?: it.dateCreated)?.toString().orEmpty() }
                    .take(ROW_LIMIT)
            }
        }

    suspend fun continueWatching(): List<BaseItemDto> =
        memoized("resume") {
            coroutineScope {
                libraries
                    .map { library ->
                        async {
                            requestCount++
                            rawApi.itemsApi
                                .getResumeItems(
                                    GetResumeItemsRequest(
                                        parentId = ItemIds.toUuid(library.libraryId),
                                        limit = PER_LIBRARY_WINDOW,
                                        fields = FIELDS,
                                        enableImageTypes = IMAGE_TYPES,
                                        imageTypeLimit = 1,
                                        enableTotalRecordCount = false,
                                    ),
                                ).content.items
                        }
                    }.awaitAll()
                    .flatten()
                    .filter { inVault(ItemRef.of(it)) }
                    .sortedByDescending {
                        it.userData
                            ?.lastPlayedDate
                            ?.toString()
                            .orEmpty()
                    }.take(ROW_LIMIT)
            }
        }

    suspend fun nextUp(): List<BaseItemDto> =
        memoized("nextUp") {
            coroutineScope {
                libraries
                    .filter { it.collectionType != MOVIES }
                    .map { library ->
                        async {
                            requestCount++
                            rawApi.tvShowsApi
                                .getNextUp(
                                    GetNextUpRequest(
                                        parentId = ItemIds.toUuid(library.libraryId),
                                        limit = PER_LIBRARY_WINDOW,
                                        fields = FIELDS,
                                        enableImageTypes = IMAGE_TYPES,
                                        imageTypeLimit = 1,
                                        enableTotalRecordCount = false,
                                    ),
                                ).content.items
                        }
                    }.awaitAll()
                    .flatten()
                    .filter { inVault(ItemRef.of(it)) }
                    .take(ROW_LIMIT)
            }
        }

    /** Titles by tag query, episodes by the index */
    suspend fun search(query: String): List<BaseItemDto> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        return coroutineScope {
            val lists =
                libraries.flatMap { library ->
                    buildList {
                        add(
                            async {
                                requestCount++
                                rawApi.itemsApi
                                    .getItems(
                                        libraryRequest(library, limit = SEARCH_LIMIT).copy(
                                            searchTerm = term,
                                            sortBy = listOf(ItemSortBy.SORT_NAME),
                                        ),
                                    ).content.items
                                    .filter { taggedIn(ItemRef.of(it), library) }
                            },
                        )
                        if (library.collectionType != MOVIES) {
                            add(
                                async {
                                    requestCount++
                                    rawApi.itemsApi
                                        .getItems(
                                            GetItemsRequest(
                                                parentId = ItemIds.toUuid(library.libraryId),
                                                recursive = true,
                                                searchTerm = term,
                                                includeItemTypes = listOf(BaseItemKind.EPISODE),
                                                filters = if (hideWatched) listOf(ItemFilter.IS_UNPLAYED) else null,
                                                limit = SEARCH_LIMIT,
                                                fields = FIELDS,
                                                enableImageTypes = IMAGE_TYPES,
                                                imageTypeLimit = 1,
                                                enableTotalRecordCount = false,
                                            ),
                                        ).content.items
                                        .filter { inVault(ItemRef.of(it)) }
                                },
                            )
                        }
                    }
                }
            val seen = mutableSetOf<String?>()
            lists.awaitAll().flatten().filter { seen.add(ItemIds.of(it.id)) }
        }
    }

    /** Forgets the rows, so the next look reads them again */
    suspend fun clear() = memoLock.withLock { memo.clear() }

    private suspend fun memoized(
        key: String,
        load: suspend () -> List<BaseItemDto>,
    ): List<BaseItemDto> {
        var owner = false
        val deferred =
            memoLock.withLock {
                memo[key] ?: CompletableDeferred<List<BaseItemDto>>().also {
                    memo[key] = it
                    owner = true
                }
            }
        if (owner) {
            val completable = deferred as CompletableDeferred<List<BaseItemDto>>
            try {
                completable.complete(load())
            } catch (ex: Throwable) {
                // A failed load is forgotten so the next look tries again
                memoLock.withLock { if (memo[key] === deferred) memo.remove(key) }
                completable.completeExceptionally(ex)
                throw ex
            }
        }
        return deferred.await()
    }

    companion object {
        const val PAGE_SIZE = 48
        const val ROW_LIMIT = 20
        const val PER_LIBRARY_WINDOW = 60
        const val SEARCH_LIMIT = 40
        private const val TV_SHOWS = "tvshows"
        private const val MOVIES = "movies"

        val FIELDS =
            listOf(
                ItemFields.TAGS,
                ItemFields.OVERVIEW,
                ItemFields.GENRES,
                ItemFields.DATE_CREATED,
                ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
                ItemFields.CHILD_COUNT,
                ItemFields.SERIES_PRIMARY_IMAGE,
                ItemFields.PARENT_ID,
                ItemFields.TRICKPLAY,
            )
        val IMAGE_TYPES = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB, ImageType.LOGO)

        fun typesFor(library: VaultLibrary): List<BaseItemKind> =
            when (library.collectionType) {
                TV_SHOWS -> listOf(BaseItemKind.SERIES)
                MOVIES -> listOf(BaseItemKind.MOVIE)
                else -> listOf(BaseItemKind.SERIES, BaseItemKind.MOVIE, BaseItemKind.VIDEO)
            }
    }
}
