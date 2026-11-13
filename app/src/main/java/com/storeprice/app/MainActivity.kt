package com.storeprice.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.storeprice.app.notifications.EXTRA_PRODUCT_ID
import com.storeprice.app.notifications.FCM_PRODUCT_ID
import com.storeprice.app.ui.StorePriceApp
import com.storeprice.app.ui.theme.StorePriceTheme

class MainActivity : ComponentActivity() {

    private var pendingProductId by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Отказ не блокирует каталог: уведомления можно включить в настройках.
    }

    private val notificationPreferences by lazy {
        getSharedPreferences(NOTIFICATION_PREFERENCES, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingProductId = productIdFrom(intent)
        requestNotificationPermissionOnce()
        enableEdgeToEdge()
        setContent {
            StorePriceTheme {
                StorePriceApp(
                    pendingProductId = pendingProductId,
                    onPendingProductHandled = {
                        pendingProductId = null
                        intent?.removeExtra(EXTRA_PRODUCT_ID)
                        intent?.removeExtra(FCM_PRODUCT_ID)
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingProductId = productIdFrom(intent)
    }

    /**
     * Возвращает id товара из собственного уведомления или из системного
     * уведомления Firebase, где data-ключи попадают в intent без префикса.
     */
    private fun productIdFrom(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_PRODUCT_ID)?.takeIf { it.isNotBlank() }
            ?: intent?.getStringExtra(FCM_PRODUCT_ID)?.takeIf { it.isNotBlank() }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted || notificationPreferences.getBoolean(KEY_PERMISSION_REQUESTED, false)) return

        notificationPreferences.edit().putBoolean(KEY_PERMISSION_REQUESTED, true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val NOTIFICATION_PREFERENCES = "storeprice_notifications"
        const val KEY_PERMISSION_REQUESTED = "permission_requested"
    }
}
