package com.github.damontecres.wholphin.custom.hiddenvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.custom.hiddenvault.HiddenVault
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.components.GridTitle
import com.github.damontecres.wholphin.ui.components.SearchEditTextBox
import com.github.damontecres.wholphin.ui.components.rememberContextMenu
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import org.jellyfin.sdk.api.client.ApiClient

/**
 * Search inside one vault: titles by the vault's tags, episodes through the index. The normal
 * search never shows any of this.
 */
@Composable
fun VaultSearchPage(
    route: VaultRoute.Search,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: VaultSearchViewModel =
        hiltViewModel<VaultSearchViewModel, VaultSearchViewModel.Factory>(
            key = route.vaultId,
            creationCallback = { it.create(route.vaultId) },
        ),
) {
    if (!viewModel.isOpen) {
        LockedPage(viewModel.navigationManager, modifier)
        return
    }
    val results by viewModel.results.collectAsState()
    val contextMenu = rememberContextMenu(preferences, viewModel)
    val textState = rememberTextFieldState()
    val searchFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.tryRequestFocus() }
    LaunchedEffect(textState) {
        snapshotFlow { textState.text.toString() }.collectLatest {
            delay(SEARCH_DEBOUNCE_MS)
            viewModel.search(it)
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.padding(16.dp),
    ) {
        GridTitle(stringResource(R.string.hidden_vault_search))
        SearchEditTextBox(
            state = textState,
            onSearchClick = { viewModel.search(textState.text.toString()) },
            modifier =
                Modifier
                    .fillMaxWidth(.5f)
                    .focusRequester(searchFocus),
        )
        VaultGrid(
            items = results,
            focusRequester = gridFocus,
            onClick = { _, item -> viewModel.navigateTo(item.destination()) },
            onLongClick = { index, item -> contextMenu.showContextMenu(index, item) },
            onPlay = { _, item -> viewModel.navigateTo(Destination.Playback(item)) },
        )
    }
    contextMenu.Compose()
}

private const val SEARCH_DEBOUNCE_MS = 400L

@HiltViewModel(assistedFactory = VaultSearchViewModel.Factory::class)
class VaultSearchViewModel
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
            fun create(vaultId: String): VaultSearchViewModel
        }

        private val _results = MutableStateFlow<List<BaseItem?>>(emptyList())
        val results: StateFlow<List<BaseItem?>> = _results

        private var lastQuery = ""
        private var job: Job? = null

        fun search(query: String) {
            val repository = repository ?: return
            if (query.trim() == lastQuery) return
            lastQuery = query.trim()
            touch()
            job?.cancel()
            job =
                viewModelScope.launchIO {
                    val found = repository.search(lastQuery)
                    client?.rememberItems(found)
                    _results.value = found.map { BaseItem(it, true) }
                }
        }

        override suspend fun onItemChanged() {
            val query = lastQuery
            lastQuery = ""
            search(query)
        }
    }
