package com.github.damontecres.wholphin.custom.hiddenvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.custom.hiddenvault.di.HiddenVaultEntryPoint
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.HiddenContentApiClient
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.UUID

/**
 * Renders the vault's own pages
 */
@Composable
fun HiddenVaultContent(
    route: VaultRoute,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
) {
    when (route) {
        VaultRoute.PrivateArea -> PrivateAreaPage(modifier)
        is VaultRoute.Editor -> VaultEditorPage(route, modifier)
        is VaultRoute.Home -> VaultHomePage(route, preferences, modifier)
        is VaultRoute.Library -> VaultLibraryPage(route, preferences, modifier)
        is VaultRoute.Search -> VaultSearchPage(route, preferences, modifier)
    }
}

/**
 * The detail gate, in front of every page that opens one item by id (details, series overview,
 * playback, slideshow): deep links, launcher rows, intents, restored back stacks and any screen
 * added upstream later.
 *
 * Returns true when the page may be shown. While the answer isn't known yet nothing is drawn, and
 * a refused item shows a neutral "not available", so neither title nor artwork ever appears.
 */
@Composable
fun rememberHiddenVaultAccess(
    destination: Destination,
    modifier: Modifier = Modifier,
): Boolean {
    val itemId = destination.gatedItemId() ?: return true
    val context = LocalContext.current
    val client =
        remember {
            try {
                EntryPointAccessors
                    .fromApplication(context.applicationContext, HiddenVaultEntryPoint::class.java)
                    .apiClient() as? HiddenContentApiClient
            } catch (ex: IllegalStateException) {
                // No Hilt application (previews): no vault either
                null
            }
        } ?: return true
    val quick = remember(itemId) { client.quickAccess(itemId) }
    val access by produceState(quick, itemId) {
        if (value == null) {
            value =
                try {
                    !client.refusesPlayback(itemId)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    // Whatever went wrong, opening the item would fail the same way
                    Timber.w(ex, "Could not check item access")
                    true
                }
        }
    }
    when (access) {
        true -> {
            return true
        }

        false -> {
            NotAvailable(modifier)
            return false
        }

        null -> {
            Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            return false
        }
    }
}

private fun Destination.gatedItemId(): UUID? =
    when (this) {
        is Destination.MediaItem -> itemId
        is Destination.SeriesOverview -> itemId
        is Destination.Playback -> itemId
        is Destination.PlaybackList -> itemId
        is Destination.Slideshow -> parentId
        else -> null
    }

@Composable
private fun NotAvailable(modifier: Modifier) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .focusRequester(focusRequester)
                .focusable(),
    ) {
        Text(
            text = stringResource(R.string.hidden_vault_unavailable),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
