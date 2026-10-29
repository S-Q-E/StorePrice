package com.storeprice.app

import android.app.Application
import com.storeprice.app.data.local.StorePriceDatabase
import com.storeprice.app.data.repository.ProductRepository
import com.storeprice.app.data.repository.RoomProductRepository

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
}
