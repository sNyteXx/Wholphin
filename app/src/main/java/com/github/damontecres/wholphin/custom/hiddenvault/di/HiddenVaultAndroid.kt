package com.github.damontecres.wholphin.custom.hiddenvault.di

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Process
import com.github.damontecres.wholphin.custom.hiddenvault.HiddenVault
import com.github.damontecres.wholphin.custom.hiddenvault.session.VaultLockEvent
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import timber.log.Timber

/**
 * Connects the platform independent [HiddenVault] to the app, without any hook in upstream code:
 *
 * * follows the signed in server and user (sign out, user and server switch lock every vault)
 * * runs the inactivity timeout
 * * leaves vault screens (and anything opened from them) the moment a vault locks
 * * listens for library changes while the app is in the foreground, so newly tagged items are
 *   hidden within seconds, and refreshes an older index when the app comes back
 */
internal class HiddenVaultAndroid(
    private val context: Context,
    private val vault: HiddenVault,
    private val rawApi: Lazy<ApiClient>,
    private val serverRepository: ServerRepository,
    private val navigationManager: NavigationManager,
    private val backdropService: BackdropService,
    private val scope: CoroutineScope,
) {
    private var socketJob: Job? = null
    private var resumed = 0

    fun start() {
        scope.launch {
            serverRepository.current.collect { current ->
                vault.setActiveScope(current?.let(::vaultScopeOf))
                if (current != null && isForeground()) listen() else stopListening()
            }
        }
        scope.launch { vault.session.lockEvents.collect { leaveLockedScreens(it) } }
        vault.session.runTicker(scope)
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(callbacks)
    }

    private suspend fun leaveLockedScreens(event: VaultLockEvent) {
        withContext(Dispatchers.Main) {
            val backStack = navigationManager.backStack
            val first =
                backStack.indexOfFirst { destination ->
                    val route = (destination as? Destination.HiddenVault)?.route ?: return@indexOfFirst false
                    // The private area closing leaves everything reached through it
                    event.vaultId == null || route.vaultId == event.vaultId
                }
            if (first < 0) return@withContext
            Timber.i("Hidden vault locked (%s), leaving its screens", event.reason)
            // The back stack must never run empty: a vault page at the bottom becomes Home
            while (backStack.size > first.coerceAtLeast(1)) backStack.removeAt(backStack.lastIndex)
            if (first == 0) backStack[0] = Destination.Home()
        }
        try {
            backdropService.clearBackdrop()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Timber.w(ex, "Could not clear backdrop")
        }
    }

    private fun listen() {
        if (socketJob?.isActive == true) return
        socketJob =
            scope.launch {
                while (true) {
                    try {
                        rawApi
                            .get()
                            .webSocket
                            .subscribe<LibraryChangedMessage>()
                            .catch { Timber.w(it, "Library change subscription failed") }
                            .collect { vault.onLibraryChanged() }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Library change subscription failed")
                    }
                    delay(RETRY_MS)
                }
            }
    }

    private fun stopListening() {
        socketJob?.cancel()
        socketJob = null
    }

    private fun isForeground(): Boolean {
        if (resumed > 0) return true
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return manager.runningAppProcesses?.any {
            it.pid == Process.myPid() && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } == true
    }

    private val callbacks =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed++
                if (serverRepository.current.value != null) listen()
                vault.currentService()?.refreshIfOlderThan(FOREGROUND_REFRESH_MS)
            }

            override fun onActivityPaused(activity: Activity) {
                resumed = (resumed - 1).coerceAtLeast(0)
                if (resumed == 0) stopListening()
            }

            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle,
            ) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        }

    companion object {
        private const val RETRY_MS = 30_000L
        private const val FOREGROUND_REFRESH_MS = 2 * 60_000L
    }
}
