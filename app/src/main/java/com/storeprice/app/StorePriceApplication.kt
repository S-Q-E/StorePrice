package com.storeprice.app

import android.app.Application
import com.storeprice.app.data.auth.AuthRepository
import com.storeprice.app.data.auth.CachedProfileStore
import com.storeprice.app.data.auth.MissingConfigAuthRepository
import com.storeprice.app.data.auth.SharedPreferencesSessionManager
import com.storeprice.app.data.auth.SupabaseAuthBackend
import com.storeprice.app.data.auth.SupabaseAuthRepository
import com.storeprice.app.data.auth.SupabaseProfileFetcher
import com.storeprice.app.data.local.StorePriceDatabase
import com.storeprice.app.data.remote.SupabaseProductRemote
import com.storeprice.app.data.repository.ProductRepository
import com.storeprice.app.data.repository.RoomProductRepository
import com.storeprice.app.data.repository.SyncingProductRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

class StorePriceApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    private val database = StorePriceDatabase.get(application)
    private val sessionManager = SharedPreferencesSessionManager(application)
    private val supabase: SupabaseClient? = createSupabaseClient()

    val productRepository: ProductRepository = createProductRepository()

    val authRepository: AuthRepository = createAuthRepository(application)

    private fun createProductRepository(): ProductRepository {
        val dao = database.productDao()
        val local = RoomProductRepository(dao)
        val client = supabase ?: return local
        return SyncingProductRepository(
            local = local,
            dao = dao,
            database = database,
            remote = SupabaseProductRemote(client),
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
        }
    }
}
