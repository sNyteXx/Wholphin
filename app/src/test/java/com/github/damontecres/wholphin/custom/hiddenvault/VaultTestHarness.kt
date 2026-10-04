package com.github.damontecres.wholphin.custom.hiddenvault

import com.github.damontecres.wholphin.custom.hiddenvault.FakeJellyfin.Companion.idOf
import com.github.damontecres.wholphin.custom.hiddenvault.data.HiddenContentService
import com.github.damontecres.wholphin.custom.hiddenvault.data.JellyfinIndexSource
import com.github.damontecres.wholphin.custom.hiddenvault.data.MemoryVaultStore
import com.github.damontecres.wholphin.custom.hiddenvault.data.VaultConfigRemote
import com.github.damontecres.wholphin.custom.hiddenvault.data.VaultScope
import com.github.damontecres.wholphin.custom.hiddenvault.model.HiddenVaultConfig
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultDefinition
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultLibrary
import com.github.damontecres.wholphin.custom.hiddenvault.session.VaultPinStore
import com.github.damontecres.wholphin.custom.hiddenvault.session.VaultSessionManager
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.HiddenContentApiClient
import kotlinx.coroutines.CoroutineScope
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import kotlin.coroutines.EmptyCoroutineContext

val SCOPE = VaultScope("server-1", "user-1")
val OTHER_USER = VaultScope("server-1", "user-2")
val OTHER_SERVER = VaultScope("server-2", "user-1")

fun lib(name: String) = idOf(name)

/**
 * Anime = Anime + Filme (Anime); Serien/Filme = Serien + Filme. Tags are only examples.
 */
fun standardConfig() =
    HiddenVaultConfig(
        vaults =
            listOf(
                VaultDefinition(
                    "anime",
                    "Anime",
                    listOf(
                        VaultLibrary(lib("lib-anime"), "Anime", "tvshows", listOf("ecchi")),
                        VaultLibrary(lib("lib-anime-movies"), "Filme (Anime)", "movies", listOf("ecchi", "private")),
                    ),
                ),
                VaultDefinition(
                    "shows",
                    "Serien/Filme",
                    listOf(
                        VaultLibrary(lib("lib-shows"), "Serien", "tvshows", listOf("private", "hidden")),
                        VaultLibrary(lib("lib-movies"), "Filme", "movies", listOf("adult")),
                    ),
                ),
            ),
    )

/**
 * The standard catalog. Under [standardConfig] the hidden items are a1, a2 (anime), m1, m2
 * (anime films), s2 (shows), f2 (films) and every episode of those.
 */
fun FakeJellyfin.seedStandard() {
    library("lib-anime", "tvshows")
    library("lib-anime-movies", "movies")
    library("lib-shows", "tvshows")
    library("lib-movies", "movies")
    library("lib-boxsets", "boxsets")
    series("lib-anime", "a1", "Ecchi", "Romance", created = "2024-05-01T00:00:00Z")
    series("lib-anime", "a2", "ECCHI", created = "2024-05-02T00:00:00Z")
    series("lib-anime", "a3", "ecchi comedy", created = "2024-05-03T00:00:00Z")
    series("lib-anime", "a4", "super-ecchi", created = "2024-05-04T00:00:00Z")
    series("lib-anime", "a5", "Action", created = "2024-05-05T00:00:00Z")
    // The server matches this loosely to "ecchi"; exact matching must not
    series("lib-anime", "a6", "Ecchi!", created = "2024-05-06T00:00:00Z")
    season("lib-anime", "a1-s1", "a1")
    episode("lib-anime", "a1e1", "a1", nextUp = true)
    episode("lib-anime", "a2e1", "a2", resume = true)
    episode("lib-anime", "a5e1", "a5", nextUp = true)
    episode("lib-anime", "a5e2", "a5", resume = true)
    movie("lib-anime-movies", "m1", "ecchi", resume = true)
    movie("lib-anime-movies", "m2", "Private")
    movie("lib-anime-movies", "m3", "Drama")
    series("lib-shows", "s1", "ecchi", created = "2024-06-01T00:00:00Z")
    series("lib-shows", "s2", "private", created = "2024-06-02T00:00:00Z")
    series("lib-shows", "s3", "hidden gem", created = "2024-06-03T00:00:00Z")
    episode("lib-shows", "s1e1", "s1", nextUp = true)
    episode("lib-shows", "s2e1", "s2", nextUp = true)
    movie("lib-movies", "f1", "adult animation")
    movie("lib-movies", "f2", "Adult")
    movie("lib-movies", "f3")
}

/** The test names of [items] */
fun names(items: List<BaseItemDto>) = items.map { FakeJellyfin.nameOf(it.id.toString()) }

fun names(result: BaseItemDtoQueryResult) = names(result.items)

/**
 * One device: its own store, session and vault over a shared [server].
 */
class Device(
    val server: FakeJellyfin,
    val background: CoroutineScope,
    var remote: VaultConfigRemote? = null,
    var now: Long = 1_790_000_000_000L,
    val store: MemoryVaultStore = MemoryVaultStore(),
) {
    lateinit var session: VaultSessionManager
    lateinit var vault: HiddenVault
    lateinit var api: HiddenContentApiClient

    init {
        start()
    }

    /** A fresh app start over the same storage: nothing in memory survives */
    fun start() {
        session = VaultSessionManager(clock = { now })
        vault =
            HiddenVault(
                serviceFactory = { scope ->
                    HiddenContentService(scope, store, JellyfinIndexSource(server), remote, background, { now })
                },
                session = session,
                pins = VaultPinStore(store, { now }, iterations = 50),
                appScope = background,
            )
        vault.setActiveScope(SCOPE)
        api = HiddenContentApiClient(server, { vault }, EmptyCoroutineContext)
    }

    val service: HiddenContentService get() = vault.currentService()!!

    suspend fun configure(config: HiddenVaultConfig = standardConfig()) = service.saveConfig(config)

    /** Unlock and open [vaultId], as its home screen does */
    fun enter(vaultId: String) {
        session.unlock(SCOPE, vaultId)
        session.enter(SCOPE, vaultId)
    }
}
