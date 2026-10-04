package com.github.damontecres.wholphin.custom.hiddenvault.di

import android.content.Context
import com.github.damontecres.wholphin.custom.hiddenvault.HiddenVault
import com.github.damontecres.wholphin.custom.hiddenvault.data.DisplayPreferencesVaultRemote
import com.github.damontecres.wholphin.custom.hiddenvault.data.FileVaultStore
import com.github.damontecres.wholphin.custom.hiddenvault.data.HiddenContentService
import com.github.damontecres.wholphin.custom.hiddenvault.data.JellyfinIndexSource
import com.github.damontecres.wholphin.custom.hiddenvault.data.VaultKeyValueStore
import com.github.damontecres.wholphin.custom.hiddenvault.data.VaultLog
import com.github.damontecres.wholphin.custom.hiddenvault.data.VaultScope
import com.github.damontecres.wholphin.custom.hiddenvault.model.ItemIds
import com.github.damontecres.wholphin.custom.hiddenvault.session.VaultPinStore
import com.github.damontecres.wholphin.custom.hiddenvault.session.VaultSessionManager
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.HiddenContentApiClient
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.DisplayPreferencesService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.hilt.IoCoroutineScope
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import timber.log.Timber
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The app's [ApiClient] without the vault's filter. Only the vault's own code may use it: the
 * index build, the vault's screens and its settings.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UnfilteredApiClient

@Module
@InstallIn(SingletonComponent::class)
object HiddenVaultModule {
    /**
     * Wraps the app's client. Called from [com.github.damontecres.wholphin.services.hilt.AppModule].
     */
    fun wrap(
        client: ApiClient,
        vault: Lazy<HiddenVault>,
    ): ApiClient = HiddenContentApiClient(client, { vault.get() }, WholphinDispatchers.Default)

    @Provides
    @Singleton
    @UnfilteredApiClient
    fun unfilteredApiClient(api: ApiClient): ApiClient = (api as? HiddenContentApiClient)?.raw ?: api

    @Provides
    @Singleton
    fun vaultStore(
        @ApplicationContext context: Context,
    ): VaultKeyValueStore = FileVaultStore(File(context.noBackupFilesDir, "hidden_vault"))

    @Provides
    @Singleton
    fun hiddenVault(
        @ApplicationContext context: Context,
        store: VaultKeyValueStore,
        @UnfilteredApiClient rawApi: Lazy<ApiClient>,
        displayPreferencesService: DisplayPreferencesService,
        serverRepository: ServerRepository,
        navigationManager: NavigationManager,
        backdropService: BackdropService,
        @IoCoroutineScope scope: CoroutineScope,
    ): HiddenVault {
        val log = VaultLog { message, error -> Timber.tag("HiddenVault").d(error, "%s", message) }
        val vault =
            HiddenVault(
                serviceFactory = { vaultScope ->
                    val userId = ItemIds.toUuid(vaultScope.userId)
                    HiddenContentService(
                        scope = vaultScope,
                        store = store,
                        indexSource = JellyfinIndexSource(rawApi.get()),
                        remote =
                            userId?.let {
                                DisplayPreferencesVaultRemote(
                                    read = {
                                        displayPreferencesService.getDisplayPreferences(
                                            userId,
                                            DisplayPreferencesVaultRemote.PREFERENCES_ID,
                                            DisplayPreferencesVaultRemote.CLIENT,
                                        )
                                    },
                                    write = { prefs ->
                                        rawApi.get().displayPreferencesApi.updateDisplayPreferences(
                                            displayPreferencesId = DisplayPreferencesVaultRemote.PREFERENCES_ID,
                                            userId = userId,
                                            client = DisplayPreferencesVaultRemote.CLIENT,
                                            data = prefs,
                                        )
                                    },
                                )
                            },
                        backgroundScope = scope,
                        log = log,
                    )
                },
                session = VaultSessionManager(),
                pins = VaultPinStore(store),
                appScope = scope,
                scopeSource = { serverRepository.current.value?.let(::vaultScopeOf) },
            )
        HiddenVaultAndroid(context, vault, rawApi, serverRepository, navigationManager, backdropService, scope).start()
        return vault
    }
}

/**
 * For composables that can't take a view model, such as the detail gate
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface HiddenVaultEntryPoint {
    fun hiddenVault(): HiddenVault

    fun apiClient(): ApiClient
}

internal fun vaultScopeOf(current: CurrentUser): VaultScope =
    VaultScope(ItemIds.of(current.server.id).orEmpty(), ItemIds.of(current.user.id).orEmpty())
