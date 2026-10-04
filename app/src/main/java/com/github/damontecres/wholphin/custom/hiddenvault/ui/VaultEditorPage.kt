package com.github.damontecres.wholphin.custom.hiddenvault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.custom.hiddenvault.HiddenVault
import com.github.damontecres.wholphin.custom.hiddenvault.di.UnfilteredApiClient
import com.github.damontecres.wholphin.custom.hiddenvault.model.ItemIds
import com.github.damontecres.wholphin.custom.hiddenvault.model.TagMatch
import com.github.damontecres.wholphin.custom.hiddenvault.model.TagPickerModel
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultDefinition
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultLibrary
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.components.EditTextBox
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.preferences.ClickPreference
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.filterApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import java.util.UUID

/**
 * Creates or edits one vault: its name, its libraries and the hidden tags of each library.
 */
@Composable
fun VaultEditorPage(
    route: VaultRoute.Editor,
    modifier: Modifier = Modifier,
    viewModel: VaultEditorViewModel =
        hiltViewModel<VaultEditorViewModel, VaultEditorViewModel.Factory>(
            key = route.editVaultId ?: "new",
            creationCallback = { it.create(route.editVaultId) },
        ),
) {
    if (!viewModel.isOpen) {
        LockedPage(viewModel.navigationManager, modifier)
        return
    }
    val state by viewModel.state.collectAsState()
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(.5f)
                    .fillMaxHeight()
                    .align(Alignment.TopEnd)
                    .background(MaterialTheme.colorScheme.surface),
        ) {
            val library = picking?.let { id -> state.libraries.firstOrNull { it.id == id } }
            when {
                state.loading -> {
                    LoadingPage()
                }

                library != null -> {
                    BackHandler { picking = null }
                    TagPicker(
                        title = stringResource(R.string.hidden_vault_hidden_tags, library.name),
                        tags = state.serverTags[library.id],
                        selected = state.selectedTags[library.id].orEmpty(),
                        onLoad = { viewModel.loadTags(library.id) },
                        onToggle = { viewModel.toggleTag(library.id, it) },
                        onAdd = { viewModel.addTag(library.id, it) },
                        onDone = { picking = null },
                    )
                }

                else -> {
                    EditorList(state, viewModel, onPickTags = { picking = it })
                }
            }
        }
    }
}

@Composable
private fun EditorList(
    state: EditorState,
    viewModel: VaultEditorViewModel,
    onPickTags: (String) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.tryRequestFocus() }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item { SectionTitle(stringResource(R.string.hidden_vault_name)) }
        item {
            EditTextBox(
                value = state.name,
                onValueChange = viewModel::setName,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(firstFocus),
            )
        }
        item { SectionTitle(stringResource(R.string.hidden_vault_libraries)) }
        items(state.libraries, key = { "lib-${it.id}" }) { library ->
            val takenBy = state.takenBy[library.id]
            val selected = library.id in state.selectedTags
            ClickPreference(
                title = (if (selected) "✓ " else "") + library.name,
                summary = takenBy?.let { stringResource(R.string.hidden_vault_library_taken, it) },
                onClick = { if (takenBy == null) viewModel.toggleLibrary(library.id) },
            )
        }
        items(state.libraries.filter { it.id in state.selectedTags }, key = { "tags-${it.id}" }) { library ->
            val tags = state.selectedTags[library.id].orEmpty()
            ClickPreference(
                title = stringResource(R.string.hidden_vault_hidden_tags, library.name),
                summary =
                    stringResource(R.string.hidden_vault_tags_selected, tags.size) +
                        if (tags.isEmpty()) "" else ": " + tags.joinToString(", "),
                onClick = { onPickTags(library.id) },
            )
        }
        item {
            ClickPreference(
                title = stringResource(if (state.saving) R.string.hidden_vault_saving else R.string.hidden_vault_save),
                onClick = viewModel::save,
            )
        }
        if (state.existing) {
            item {
                ClickPreference(
                    title = stringResource(R.string.hidden_vault_delete_area),
                    onClick = viewModel::delete,
                )
            }
        }
    }
}

/**
 * Picks hidden tags from the tags that really exist in a library, plus manual entry.
 *
 * Built for hundreds of tags on a TV: a lazy list that only composes what is on screen, a filter
 * that applies once typing pauses, and selecting a tag never moves it, so focus stays put.
 */
@Composable
private fun TagPicker(
    title: String,
    tags: List<String>?,
    selected: List<String>,
    onLoad: () -> Unit,
    onToggle: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDone: () -> Unit,
) {
    LaunchedEffect(Unit) { onLoad() }
    var filter by rememberSaveable { mutableStateOf("") }
    var appliedFilter by remember { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        snapshotFlow { filter }.collectLatest {
            delay(TAG_FILTER_DEBOUNCE_MS)
            appliedFilter = it
        }
    }
    // Tags typed by hand stay in the list for the whole visit, so ticking never moves a row
    var extras by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(selected, tags) {
        // Only once the server's tags are known, or every selected tag would count as typed
        if (tags != null) extras = TagPickerModel.extras(selected, tags, extras)
    }
    val selectedNormalized = remember(selected) { selected.map(TagMatch::normalize).toSet() }
    val shown = remember(tags, extras, appliedFilter) { TagPickerModel.rows(tags.orEmpty(), extras, appliedFilter) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.tryRequestFocus() }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(16.dp),
    ) {
        SectionTitle(title)
        Text(
            text = stringResource(R.string.hidden_vault_tags_selected, selected.size),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EditTextBox(
            value = filter,
            onValueChange = { filter = it },
            placeholder = { Text(stringResource(R.string.hidden_vault_filter_tags)) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(firstFocus),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            EditTextBox(
                value = manual,
                onValueChange = { manual = it },
                placeholder = { Text(stringResource(R.string.hidden_vault_add_tag)) },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    if (manual.isNotBlank()) {
                        onAdd(manual.trim())
                        manual = ""
                    }
                },
            ) { Text("+") }
            TextButton(onClick = onDone) { Text(stringResource(R.string.hidden_vault_done)) }
        }
        when {
            tags == null -> {
                LoadingPage(focusEnabled = false)
            }

            shown.isEmpty() -> {
                SectionHint(stringResource(R.string.hidden_vault_no_tags))
            }

            else -> {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(shown, key = { it }) { tag ->
                        val isSelected = TagMatch.normalize(tag) in selectedNormalized
                        ListItem(
                            selected = isSelected,
                            onClick = { onToggle(tag) },
                            headlineContent = { Text(tag) },
                            trailingContent = {
                                if (isSelected) Icon(imageVector = Icons.Default.Check, contentDescription = null)
                            },
                        )
                    }
                }
            }
        }
    }
}

const val TAG_FILTER_DEBOUNCE_MS = 200L

data class EditorLibrary(
    val id: String,
    val name: String,
    val collectionType: String?,
)

data class EditorState(
    val loading: Boolean = true,
    val existing: Boolean = false,
    val name: String = "",
    val libraries: List<EditorLibrary> = emptyList(),
    /** Selected libraries and their tags, in the spelling shown */
    val selectedTags: Map<String, List<String>> = emptyMap(),
    /** Libraries owned by another vault, with that vault's name */
    val takenBy: Map<String, String> = emptyMap(),
    /** Tags the server knows per library, null while loading */
    val serverTags: Map<String, List<String>?> = emptyMap(),
    val saving: Boolean = false,
)

@HiltViewModel(assistedFactory = VaultEditorViewModel.Factory::class)
class VaultEditorViewModel
    @AssistedInject
    constructor(
        private val vault: HiddenVault,
        @param:UnfilteredApiClient private val rawApi: ApiClient,
        val navigationManager: NavigationManager,
        @Assisted private val editVaultId: String?,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(editVaultId: String?): VaultEditorViewModel
        }

        private val scope = vault.activeScope.value
        private val service = vault.currentService()
        val isOpen: Boolean = scope != null && service != null && vault.session.isPrivateAreaOpen(scope)

        private val _state = MutableStateFlow(EditorState())
        val state: StateFlow<EditorState> = _state

        init {
            if (isOpen) load()
        }

        private fun load() {
            val service = service ?: return
            viewModelScope.launchIO {
                val config = service.config
                val current = editVaultId?.let { config.vault(it) }
                val views =
                    rawApi.userViewsApi
                        .getUserViews()
                        .content.items
                        .mapNotNull { view ->
                            ItemIds.of(view.id)?.let { EditorLibrary(it, view.name.orEmpty(), view.collectionType?.serialName) }
                        }
                // Configured libraries the views don't list (hidden from the home screen, say)
                // must not be dropped by the next save
                val known = views.mapTo(HashSet()) { it.id }
                val libraries =
                    views +
                        current
                            ?.libraries
                            .orEmpty()
                            .filter { it.libraryId !in known }
                            .map { EditorLibrary(it.libraryId, it.name, it.collectionType) }
                val takenBy =
                    config.vaults
                        .filter { it.id != editVaultId }
                        .flatMap { other -> other.libraries.map { it.libraryId to other.name } }
                        .toMap()
                _state.value =
                    EditorState(
                        loading = false,
                        existing = current != null,
                        name = current?.name.orEmpty(),
                        libraries = libraries,
                        selectedTags = current?.libraries?.associate { it.libraryId to it.tags }.orEmpty(),
                        takenBy = takenBy,
                    )
            }
        }

        private fun touched() = vault.session.touchPrivateArea()

        fun setName(name: String) {
            touched()
            _state.update { it.copy(name = name) }
        }

        fun toggleLibrary(libraryId: String) {
            touched()
            _state.update { state ->
                val selected = state.selectedTags.toMutableMap()
                if (selected.remove(libraryId) == null) selected[libraryId] = emptyList()
                state.copy(selectedTags = selected)
            }
        }

        fun loadTags(libraryId: String) {
            if (_state.value.serverTags[libraryId] != null) return
            viewModelScope.launchIO {
                // Unfiltered on purpose: the app's filter keeps hidden tags out of filter lists
                val tags =
                    try {
                        rawApi.filterApi
                            .getQueryFiltersLegacy(parentId = ItemIds.toUuid(libraryId))
                            .content.tags
                            .orEmpty()
                            .sortedWith(String.CASE_INSENSITIVE_ORDER)
                    } catch (ex: Exception) {
                        emptyList()
                    }
                _state.update { it.copy(serverTags = it.serverTags + (libraryId to tags)) }
            }
        }

        fun toggleTag(
            libraryId: String,
            tag: String,
        ) {
            touched()
            _state.update { state ->
                val next = TagPickerModel.toggle(state.selectedTags[libraryId].orEmpty(), tag)
                state.copy(selectedTags = state.selectedTags + (libraryId to next))
            }
        }

        fun addTag(
            libraryId: String,
            tag: String,
        ) {
            touched()
            _state.update { state ->
                val next = TagPickerModel.add(state.selectedTags[libraryId].orEmpty(), tag)
                state.copy(selectedTags = state.selectedTags + (libraryId to next))
            }
        }

        fun save() {
            val service = service ?: return
            val state = _state.value
            if (state.saving || state.name.isBlank()) return
            touched()
            _state.update { it.copy(saving = true) }
            viewModelScope.launchIO {
                val definition =
                    VaultDefinition(
                        id = editVaultId ?: UUID.randomUUID().toString(),
                        name = state.name.trim(),
                        libraries =
                            state.libraries
                                .filter { it.id in state.selectedTags }
                                .map { VaultLibrary(it.id, it.name, it.collectionType, state.selectedTags[it.id].orEmpty()) },
                    )
                val config = service.config
                val vaults =
                    if (config.vault(definition.id) != null) {
                        config.vaults.map { if (it.id == definition.id) definition else it }
                    } else {
                        config.vaults + definition
                    }
                service.saveConfig(config.copy(vaults = vaults))
                vault.onConfigSaved()
                _state.update { it.copy(saving = false) }
                withContext(Dispatchers.Main) { navigationManager.goBack() }
            }
        }

        fun delete() {
            val service = service ?: return
            val id = editVaultId ?: return
            touched()
            viewModelScope.launchIO {
                val config = service.config
                service.saveConfig(config.copy(vaults = config.vaults.filter { it.id != id }))
                vault.onConfigSaved()
                withContext(Dispatchers.Main) { navigationManager.goBack() }
            }
        }
    }
