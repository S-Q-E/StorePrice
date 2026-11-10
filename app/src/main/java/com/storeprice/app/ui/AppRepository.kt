package com.storeprice.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.storeprice.app.StorePriceApplication
import com.storeprice.app.data.auth.AuthRepository
import com.storeprice.app.data.repository.ProductRepository

@Composable
fun rememberProductRepository(): ProductRepository {
    val context = LocalContext.current
    return remember(context) {
        (context.applicationContext as StorePriceApplication).container.productRepository
    }
}

@Composable
fun rememberAuthRepository(): AuthRepository {
    val context = LocalContext.current
    return remember(context) {
        (context.applicationContext as StorePriceApplication).container.authRepository
    }
}
