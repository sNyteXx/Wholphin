package com.github.damontecres.wholphin.custom.hiddenvault

import com.github.damontecres.wholphin.custom.hiddenvault.model.ItemIds
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.HiddenContentApiClient
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.ItemRef
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.PagedQueryInfo
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.VisiblePaging
import com.github.damontecres.wholphin.util.GetEpisodesRequestHandler
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.GetNextUpRequestHandler
import com.github.damontecres.wholphin.util.GetPlaylistItemsRequestHandler
import com.github.damontecres.wholphin.util.GetResumeItemsRequestHandler
import com.github.damontecres.wholphin.util.GetSuggestionsRequestHandler
import com.github.damontecres.wholphin.util.RequestHandler
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetEpisodesRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest

/**
 * Every call upstream Wholphin code makes into the vault. Each call site is marked with
 * `hidden-vault:` so `git grep "hidden-vault:"` finds them all after an upstream merge.
 *
 * Everything here degrades to "no vault" when the app's [ApiClient] isn't the vault's (unit tests
 * that hand in a mock), so upstream tests keep working unchanged.
 */
object HiddenVaultHooks {
    /**
     * Exact paging for an [com.github.damontecres.wholphin.util.ApiRequestPager], or null when
     * the pager should load the normal way (no vault, or a list that never holds media items).
     */
    fun <T> pagingFor(
        api: ApiClient,
        request: T,
        handler: RequestHandler<T>,
    ): VisiblePaging<T>? {
        val client = api as? HiddenContentApiClient ?: return null
        val info =
            when (handler) {
                is GetItemsRequestHandler -> {
                    val items = request as GetItemsRequest
                    PagedQueryInfo(
                        anchorId = ItemIds.of(items.parentId),
                        namesIds = !items.ids.isNullOrEmpty(),
                        unstableOrder = items.sortBy?.contains(ItemSortBy.RANDOM) == true,
                    )
                }

                is GetEpisodesRequestHandler -> {
                    PagedQueryInfo(anchorId = ItemIds.of((request as GetEpisodesRequest).seriesId))
                }

                is GetNextUpRequestHandler -> {
                    PagedQueryInfo(anchorId = ItemIds.of((request as GetNextUpRequest).seriesId))
                }

                is GetResumeItemsRequestHandler,
                is GetSuggestionsRequestHandler,
                is GetPlaylistItemsRequestHandler,
                -> {
                    PagedQueryInfo()
                }

                // Genres, studios, persons, artists, live TV: no library media items
                else -> {
                    return null
                }
            }
        return VisiblePaging(
            client,
            info,
            { start, limit, total -> handler.prepare(request, start, limit, total) },
            { apiClient, prepared -> handler.execute(apiClient, prepared).content },
        )
    }

    /**
     * The playback gate: whether [item] may not be played right now. Checked for every item the
     * player is about to start (first item, next, previous, auto play, queue entries).
     */
    suspend fun refusesPlayback(
        api: ApiClient,
        item: BaseItemDto,
    ): Boolean {
        val client = api as? HiddenContentApiClient ?: return false
        return client.refusesPlayback(ItemRef.of(item))
    }
}
