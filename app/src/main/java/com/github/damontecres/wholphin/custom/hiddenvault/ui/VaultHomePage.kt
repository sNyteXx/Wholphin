package com.github.damontecres.wholphin.custom.hiddenvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.custom.hiddenvault.HiddenVault
import com.github.damontecres.wholphin.custom.hiddenvault.session.VaultLockReason
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.components.rememberContextMenu
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.main.HomePageContent
import com.github.damontecres.wholphin.ui.main.HomePageHeader
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * A vault's own home, built from the home screen's parts: the same header with the focused
 * title, the same rows, cards, focus handling, backdrop and long press menu. Only this vault's
 * hidden content appears here.
 */
@Composable
fun VaultHomePage(
    route: VaultRoute.Home,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: VaultHomeViewModel =
        hiltViewModel<VaultHomeViewModel, VaultHomeViewModel.Factory>(
            key = route.vaultId,
            creationCallback = { it.create(route.vaultId) },
        ),
) {
    if (!viewModel.isOpen) {
        LockedPage(viewModel.navigationManager, modifier)
        return
    }
    val rows by viewModel.rows.collectAsState()
    val currentRows by rememberUpdatedState(rows)
    if (rows == null) {
        LoadingPage(modifier)
        return
    }
    var position by rememberPosition()
    val contextMenu = rememberContextMenu(preferences, viewModel)
    val showLogo = preferences.appPreferences.interfacePreferences.showLogos
    HomePageContent(
        homeRows = rows!!,
        position = position,
        onFocusPosition = { position = it },
        onClickItem = { clicked: RowColumn, item: BaseItem ->
            position = clicked
            viewModel.navigateTo(item.destination())
        },
        onLongClickItem = { clicked: RowColumn, item: BaseItem ->
            position = clicked
            contextMenu.showContextMenu(clicked.column, item)
        },
        onClickPlay = { _: RowColumn, item: BaseItem -> viewModel.navigateTo(Destination.Playback(item)) },
        showClock = preferences.appPreferences.interfacePreferences.showClock,
        onUpdateBackdrop = viewModel::updateBackdrop,
        showLogo = showLogo,
        showViewMore = true,
        onClickViewMore = { clicked: RowColumn, _ -> viewModel.openLibraryOfRow(currentRows.orEmpty(), clicked.row) },
        headerComposable = { focusedItem ->
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 8.dp, top = 16.dp),
                ) {
                    TextButton(onClick = viewModel::openSearch) { Text(stringResource(R.string.hidden_vault_search)) }
                    viewModel.libraries.forEach { (libraryId, name) ->
                        TextButton(onClick = { viewModel.openLibrary(libraryId) }) { Text(name) }
                    }
                    TextButton(onClick = viewModel::lock) { Text(stringResource(R.string.hidden_vault_lock_now)) }
                }
                HomePageHeader(item = focusedItem, showLogo = showLogo, modifier = HeaderUtils.modifier)
            }
        },
        modifier = modifier,
    )
    contextMenu.Compose()
}

@HiltViewModel(assistedFactory = VaultHomeViewModel.Factory::class)
class VaultHomeViewModel
    @AssistedInject
    constructor(
        vault: HiddenVault,
        api: ApiClient,
        navigationManager: NavigationManager,
        backdropService: BackdropService,
        favoriteWatchManager: FavoriteWatchManager,
        @Assisted vaultId: String,
    ) : VaultScreenViewModel(vault, api, navigationManager, backdropService, favoriteWatchManager, vaultId) {
        @AssistedFactory
        interface Factory {
            fun create(vaultId: String): VaultHomeViewModel
        }

        private val _rows = MutableStateFlow<List<HomeRowLoadingState>?>(null)
        val rows: StateFlow<List<HomeRowLoadingState>?> = _rows

        /** Library ids and names of this vault, for the buttons and the rows */
        val libraries: List<Pair<String, String>> = repository?.libraries?.map { it.libraryId to it.name }.orEmpty()

        /** Row index to library id, for "view more" */
        private var libraryRows: Map<Int, String> = emptyMap()

        init {
            load()
        }

        private fun load() {
            val repository = repository ?: return
            viewModelScope.launchIO {
                val resume = async { runCatching { repository.continueWatching() }.getOrDefault(emptyList()) }
                val nextUp = async { runCatching { repository.nextUp() }.getOrDefault(emptyList()) }
                val recent = async { runCatching { repository.recentlyAdded() }.getOrDefault(emptyList()) }
                val perLibrary =
                    repository.libraries.map { library ->
                        library to async { runCatching { repository.libraryRow(library) }.getOrDefault(emptyList()) }
                    }
                val rows = mutableListOf<HomeRowLoadingState>()
                val indexByLibrary = mutableMapOf<Int, String>()

                fun add(
                    row: HomeRowLoadingState.Success,
                    libraryId: String? = null,
                ) {
                    if (row.items.isEmpty()) return
                    libraryId?.let { indexByLibrary[rows.size] = it }
                    rows.add(row)
                }
                add(
                    HomeRowLoadingState.Success(
                        ResStringProvider(R.string.hidden_vault_continue_watching),
                        resume.await().toItems(),
                        showViewMore = false,
                    ),
                )
                add(
                    HomeRowLoadingState.Success(
                        ResStringProvider(R.string.hidden_vault_next_up),
                        nextUp.await().toItems(),
                        showViewMore = false,
                    ),
                )
                add(
                    HomeRowLoadingState.Success(
                        ResStringProvider(R.string.hidden_vault_recently_added),
                        recent.await().toItems(),
                        showViewMore = false,
                    ),
                )
                perLibrary.forEach { (library, items) ->
                    add(HomeRowLoadingState.Success(StringStringProvider(library.name), items.await().toItems()), library.libraryId)
                }
                libraryRows = indexByLibrary
                _rows.value = rows
            }
        }

        private fun List<BaseItemDto>.toItems(): List<BaseItem?> {
            client?.rememberItems(this)
            return map { BaseItem(it, true) }
        }

        override suspend fun onItemChanged() {
            repository?.clear()
            load()
        }

        fun openLibraryOfRow(
            rows: List<HomeRowLoadingState>,
            row: Int,
        ) {
            libraryRows[row]?.let { openLibrary(it) }
        }

        fun openLibrary(libraryId: String) {
            navigateTo(Destination.HiddenVault(VaultRoute.Library(vaultId, libraryId)))
        }

        fun openSearch() {
            navigateTo(Destination.HiddenVault(VaultRoute.Search(vaultId)))
        }

        fun lock() {
            scope?.let { vault.session.lock(it, vaultId, VaultLockReason.MANUAL) }
        }
    }
