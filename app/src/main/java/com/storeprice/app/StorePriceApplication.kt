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
import com.storeprice.app.data.repository.ProductRepository
import com.storeprice.app.data.repository.RoomProductRepository
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

    val productRepository: ProductRepository = RoomProductRepository(database.productDao())

    val authRepository: AuthRepository = createAuthRepository(application)

    private fun createAuthRepository(application: Application): AuthRepository {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return MissingConfigAuthRepository()
        }

        val sessionManager = SharedPreferencesSessionManager(application)
        val supabase = createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Auth) {
                this.sessionManager = sessionManager
                codeVerifierCache = MemoryCodeVerifierCache()
            }
            install(Postgrest)
        }

        return SupabaseAuthRepository(
            backend = SupabaseAuthBackend(supabase, sessionManager),
            profileCache = CachedProfileStore(application),
            profileFetcher = SupabaseProfileFetcher(supabase),
        )
    }
}
