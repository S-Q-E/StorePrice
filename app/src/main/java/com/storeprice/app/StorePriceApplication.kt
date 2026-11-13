package com.storeprice.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.storeprice.app.data.auth.AuthRepository
import com.storeprice.app.data.auth.CachedProfileStore
import com.storeprice.app.data.auth.MissingConfigAuthRepository
import com.storeprice.app.data.auth.SharedPreferencesSessionManager
import com.storeprice.app.data.auth.SupabaseAuthBackend
import com.storeprice.app.data.auth.SupabaseAuthRepository
import com.storeprice.app.data.auth.SupabaseProfileFetcher
import com.storeprice.app.data.local.StorePriceDatabase
import com.storeprice.app.data.remote.ProductRealtimeSource
import com.storeprice.app.data.remote.SupabaseProductRemote
import com.storeprice.app.data.repository.ProductRepository
import com.storeprice.app.data.repository.RoomProductRepository
import com.storeprice.app.data.repository.SyncingProductRepository
import com.storeprice.app.data.sync.NetworkMonitor
import com.storeprice.app.data.sync.SharedPreferencesSyncMetadataStore
import com.storeprice.app.domain.auth.AuthState
import com.storeprice.app.domain.model.EmployeeRole
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StorePriceApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(private val application: Application) {
    // The connectivity callback is registered from this scope; keeping it on
    // the main dispatcher matches Android's lifecycle/looper expectations.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val database = StorePriceDatabase.get(application)
    private val sessionManager = SharedPreferencesSessionManager(application)
    private val supabase: SupabaseClient? = createSupabaseClient()
    private val networkMonitor = NetworkMonitor(application)

    /** Process-wide network state used by the catalog banner and auto-refresh. */
    val networkAvailable: StateFlow<Boolean> = networkMonitor.isOnline()
        .stateIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            initialValue = networkMonitor.isOnlineNow(),
        )

    val authRepository: AuthRepository = createAuthRepository(application)

    val productRepository: ProductRepository = createProductRepository()

    init {
        startCatalogUpdates()
    }

    private fun createProductRepository(): ProductRepository {
        val dao = database.productDao()
        val local = RoomProductRepository(dao)
        val client = supabase ?: return local
        return SyncingProductRepository(
            local = local,
            dao = dao,
            database = database,
            remote = SupabaseProductRemote(client),
            metadata = SharedPreferencesSyncMetadataStore(application),
            canWrite = {
                val authState = authRepository.state.value
                authState is AuthState.SignedIn &&
                    authState.employee.role == EmployeeRole.ADMIN &&
                    !authState.isOffline
            },
        )
    }

    private fun createAuthRepository(application: Application): AuthRepository {
        val client = supabase ?: return MissingConfigAuthRepository()
        return SupabaseAuthRepository(
            backend = SupabaseAuthBackend(client, sessionManager),
            profileCache = CachedProfileStore(application),
            profileFetcher = SupabaseProfileFetcher(client),
        )
    }

    private fun createSupabaseClient(): SupabaseClient? {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return null
        }

        return createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Auth) {
                this.sessionManager = this@AppContainer.sessionManager
                codeVerifierCache = MemoryCodeVerifierCache()
            }
            install(Postgrest)
            install(Realtime)
        }
    }

    /**
     * Keeps the shared catalog current while the app is in the foreground.
     *
     * Realtime is only a trigger: every change runs the same incremental Room
     * sync as a manual refresh. Network recovery also triggers a sync, so a
     * device that was offline catches up without waiting for a Realtime event.
     * Both collectors are cancelled when the process leaves the foreground, and
     * Realtime is disconnected through its flow's `finally` block.
     */
    @OptIn(FlowPreview::class)
    private fun startCatalogUpdates() {
        val client = supabase ?: return
        val realtimeSource = ProductRealtimeSource(client)
        val signedIn = authRepository.state
            .map { it is AuthState.SignedIn }
            .distinctUntilChanged()

        val processLifecycleOwner = ProcessLifecycleOwner.get()
        processLifecycleOwner.lifecycleScope.launch {
            processLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    networkAvailable
                        .combine(signedIn) { online, hasSession -> online && hasSession }
                        .distinctUntilChanged()
                        .filter { it }
                        .debounce(NETWORK_DEBOUNCE_MILLIS)
                        .collect {
                            val authState = authRepository.state.value
                            if (authState is AuthState.SignedIn && authState.isOffline) {
                                authRepository.refreshSessionSilently()
                            }
                            if (authRepository.state.value is AuthState.SignedIn) {
                                productRepository.syncCatalog()
                            }
                        }
                }
                launch {
                    signedIn.collectLatest { hasSession ->
                        if (!hasSession) return@collectLatest
                        realtimeSource.changes()
                            .retryWhen { cause, attempt ->
                                if (cause is CancellationException) {
                                    false
                                } else {
                                    Log.w(TAG, "Realtime connection failed; retrying", cause)
                                    delay(realtimeRetryDelayMillis(attempt))
                                    true
                                }
                            }
                            .debounce(REALTIME_DEBOUNCE_MILLIS)
                            .collect {
                                productRepository.syncCatalog()
                            }
                    }
                }
            }
        }
    }

    private fun realtimeRetryDelayMillis(attempt: Long): Long {
        val exponent = attempt.coerceAtMost(5).toInt()
        return (1_000L shl exponent).coerceAtMost(30_000L)
    }

    private companion object {
        const val TAG = "CatalogUpdates"
        const val NETWORK_DEBOUNCE_MILLIS = 500L
        const val REALTIME_DEBOUNCE_MILLIS = 300L
    }
}
