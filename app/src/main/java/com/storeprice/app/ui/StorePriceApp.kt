package com.storeprice.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.storeprice.app.domain.auth.AuthState
import com.storeprice.app.domain.model.EmployeeProfile
import com.storeprice.app.ui.auth.AuthLoadingScreen
import com.storeprice.app.ui.auth.AuthViewModel
import com.storeprice.app.ui.auth.LoginScreen
import com.storeprice.app.ui.auth.SessionUnavailableScreen
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
    val authViewModel: AuthViewModel = viewModel(
        factory = AuthViewModel.factory(rememberAuthRepository()),
    )
    val uiState by authViewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, authViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                authViewModel.onAppResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    when (val authState = uiState.authState) {
        AuthState.Initializing -> AuthLoadingScreen(modifier = modifier)

        is AuthState.SignedOut -> LoginScreen(
            uiState = uiState,
            onPhoneChange = authViewModel::onPhoneChange,
            onPasswordChange = authViewModel::onPasswordChange,
            onSignIn = authViewModel::signIn,
            modifier = modifier,
        )

        is AuthState.SignedIn -> CatalogApp(
            employee = authState.employee,
            isOffline = authState.isOffline,
            // На этапе 4 каталог ещё локальный, поэтому редактирование разрешено.
            // После подключения Supabase в офлайне его нужно ограничить или
            // ставить изменения в очередь.
            canEdit = true,
            onSignOut = authViewModel::signOut,
            modifier = modifier,
        )

        is AuthState.Unavailable -> SessionUnavailableScreen(
            reason = authState.reason,
            onRetry = authViewModel::retrySession,
            onSignOut = authViewModel::signOut,
            modifier = modifier,
        )
    }
}

@Composable
private fun CatalogApp(
    employee: EmployeeProfile,
    onSignOut: () -> Unit,
    isOffline: Boolean,
    canEdit: Boolean,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.PRODUCTS,
        modifier = modifier,
    ) {
        composable(Routes.PRODUCTS) {
            ProductListScreen(
                employee = employee,
                isOffline = isOffline,
                canEdit = canEdit,
                onAddProduct = { navController.navigate(Routes.newProduct()) },
                onProductClick = { id ->
                    navController.navigate(Routes.productDetail(id))
                },
                onScanBarcode = { navController.navigate(Routes.SCANNER) },
                onSignOut = onSignOut,
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
