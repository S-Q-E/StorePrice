package com.storeprice.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.storeprice.app.ui.product.ProductDetailScreen
import com.storeprice.app.ui.product.ProductEditorScreen
import com.storeprice.app.ui.products.ProductListScreen
import com.storeprice.app.ui.scanner.ScannerScreen

private object Routes {
    const val PRODUCTS = "products"
    const val SCANNER = "scanner"
    const val NEW_PRODUCT = "product/new?barcode={barcode}"
    const val EDIT_PRODUCT = "product/{productId}/edit"
    const val PRODUCT_DETAIL = "product/{productId}"

    fun newProduct(barcode: String? = null): String =
        if (barcode.isNullOrBlank()) {
            "product/new"
        } else {
            "product/new?barcode=${Uri.encode(barcode)}"
        }

    fun editProduct(id: String): String = "product/$id/edit"
    fun productDetail(id: String): String = "product/$id"
}

@Composable
fun StorePriceApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.PRODUCTS,
        modifier = modifier,
    ) {
        composable(Routes.PRODUCTS) {
            ProductListScreen(
                onAddProduct = { navController.navigate(Routes.newProduct()) },
                onProductClick = { id ->
                    navController.navigate(Routes.productDetail(id))
                },
                onScanBarcode = { navController.navigate(Routes.SCANNER) },
            )
        }

        composable(Routes.SCANNER) {
            ScannerScreen(
                onBack = { navController.popBackStack() },
                onExistingProduct = { id ->
                    navController.navigate(Routes.productDetail(id)) {
                        popUpTo(Routes.SCANNER) { inclusive = true }
                    }
                },
                onNewProduct = { barcode ->
                    navController.navigate(Routes.newProduct(barcode)) {
                        popUpTo(Routes.SCANNER) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.NEW_PRODUCT,
            arguments = listOf(
                navArgument("barcode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val scannedBarcode = backStackEntry.arguments?.getString("barcode")
            ProductEditorScreen(
                productId = null,
                initialBarcode = scannedBarcode,
                onBack = { navController.popBackStack() },
                onSaved = { id ->
                    navController.navigate(Routes.productDetail(id)) {
                        popUpTo(Routes.NEW_PRODUCT) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.EDIT_PRODUCT,
            arguments = listOf(navArgument("productId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val productId = requireNotNull(backStackEntry.arguments?.getString("productId"))
            ProductEditorScreen(
                productId = productId,
                onBack = { navController.popBackStack() },
                onSaved = { _ -> navController.popBackStack() },
            )
        }

        composable(
            route = Routes.PRODUCT_DETAIL,
            arguments = listOf(navArgument("productId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val productId = requireNotNull(backStackEntry.arguments?.getString("productId"))
            ProductDetailScreen(
                productId = productId,
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Routes.editProduct(id)) },
                onDeleted = {
                    if (!navController.popBackStack(Routes.PRODUCTS, inclusive = false)) {
                        navController.navigate(Routes.PRODUCTS)
                    }
                },
            )
        }
    }
}
