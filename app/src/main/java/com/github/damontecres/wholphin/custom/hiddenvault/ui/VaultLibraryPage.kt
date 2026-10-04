package com.github.damontecres.wholphin.custom.hiddenvault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.custom.hiddenvault.HiddenVault
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.PagedQueryInfo
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.VisiblePaging
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.cards.GridCard
import com.github.damontecres.wholphin.ui.components.GridTitle
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.rememberContextMenu
import com.github.damontecres.wholphin.ui.detail.CardGrid
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.QueryResult
import com.github.damontecres.wholphin.util.RequestPager
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.request.GetItemsRequest

/**
 * A vault library as a grid: Wholphin's own card grid and cards, paged exactly over the server's
 * tag query (only this library's hidden titles travel).
 */
@Composable
fun VaultLibraryPage(
    route: VaultRoute.Library,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: VaultLibraryViewModel =
        hiltViewModel<VaultLibraryViewModel, VaultLibraryViewModel.Factory>(
            key = "${route.vaultId}/${route.libraryId}",
            creationCallback = { it.create(route.vaultId, route.libraryId) },
        ),
) {
    if (!viewModel.isOpen) {
        LockedPage(viewModel.navigationManager, modifier)
        return
    }
    val pager by viewModel.pager.collectAsState()
    val contextMenu = rememberContextMenu(preferences, viewModel)
    val current = pager
    if (current == null) {
        LoadingPage(modifier)
        return
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
    Column(modifier = modifier.padding(16.dp)) {
        GridTitle(viewModel.title)
        VaultGrid(
            items = current,
            focusRequester = focusRequester,
            onClick = { _, item -> viewModel.navigateTo(item.destination()) },
            onLongClick = { index, item -> contextMenu.showContextMenu(index, item) },
            onPlay = { _, item -> viewModel.navigateTo(Destination.Playback(item)) },
        )
    }
    contextMenu.Compose()
}

/** The card grid used by the vault's library and search pages */
@Composable
internal fun VaultGrid(
    items: List<BaseItem?>,
    focusRequester: FocusRequester,
    onClick: (Int, BaseItem) -> Unit,
    onLongClick: (Int, BaseItem) -> Unit,
    onPlay: (Int, BaseItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    CardGrid(
        pager = items,
        onClickItem = onClick,
        onLongClickItem = onLongClick,
        onClickPlay = onPlay,
        letterPosition = { 0 },
        gridFocusRequester = focusRequester,
        showJumpButtons = false,
        showLetterButtons = false,
        modifier = modifier,
        cardContent = { details ->
            GridCard(
                item = details.item,
                onClick = details.onClick,
                onLongClick = details.onLongClick,
                fillWidth = details.widthPx,
                modifier = details.mod,
            )
        },
    )
}

/**
 * Pages a vault library with the same exact paging as the rest of the app, selecting the items
 * tagged for this library instead of hiding them.
 */
internal class VaultRequestPager(
    scope: CoroutineScope,
    private val paging: VisiblePaging<GetItemsRequest>,
    private val onLoaded: (List<BaseItemDto>) -> Unit,
) : RequestPager<BaseItem>(scope, PAGE_SIZE) {
    override suspend fun fetchPage(
        pageNumber: Int,
        includeTotalCount: Boolean,
    ): QueryResult<BaseItem> {
        val page = paging.fetchPage(pageNumber, pageSize, includeTotalCount) ?: return QueryResult(emptyList(), 0)
        onLoaded(page.items)
        return QueryResult(page.items.map { BaseItem(it, true) }, page.totalCount)
    }

    companion object {
        const val PAGE_SIZE = 48
    }
}

@HiltViewModel(assistedFactory = VaultLibraryViewModel.Factory::class)
class VaultLibraryViewModel
    @AssistedInject
    constructor(
        vault: HiddenVault,
        api: ApiClient,
        navigationManager: NavigationManager,
        backdropService: BackdropService,
        favoriteWatchManager: FavoriteWatchManager,
        @Assisted("vault") vaultId: String,
        @Assisted("library") private val libraryId: String,
    ) : VaultScreenViewModel(vault, api, navigationManager, backdropService, favoriteWatchManager, vaultId) {
        @AssistedFactory
        interface Factory {
            fun create(
                @Assisted("vault") vaultId: String,
                @Assisted("library") libraryId: String,
            ): VaultLibraryViewModel
        }

        private val library = repository?.vault?.library(libraryId)
        val title: String = library?.name.orEmpty()

        private val _pager = MutableStateFlow<RequestPager<BaseItem>?>(null)
        val pager: StateFlow<RequestPager<BaseItem>?> = _pager

        init {
            load()
        }

        private fun load() {
            val repository = repository ?: return
            val library = library ?: return
            val client = client ?: return
            viewModelScope.launchIO {
                val paging =
                    VisiblePaging(
                        client = client,
                        info = PagedQueryInfo(),
                        prepare = { start, limit, total ->
                            repository.libraryRequest(library, start, limit).copy(enableTotalRecordCount = total)
                        },
                        execute = { api, request -> api.itemsApi.getItems(request).content },
                        vaultSelector = { ref -> repository.taggedIn(ref, library) },
                    )
                _pager.value = VaultRequestPager(viewModelScope, paging, client::rememberItems).init()
            }
        }

        override suspend fun onItemChanged() = load()
    }
