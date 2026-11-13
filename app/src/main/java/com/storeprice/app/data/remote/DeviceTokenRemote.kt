package com.storeprice.app.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Регистрация FCM-токена текущего сотрудника на этом устройстве. */
internal interface DeviceTokenRemote {
    suspend fun register(fcmToken: String, deviceId: String)

    suspend fun unregister(deviceId: String)
}

internal class SupabaseDeviceTokenRemote(
    private val supabase: SupabaseClient,
) : DeviceTokenRemote {

    override suspend fun register(fcmToken: String, deviceId: String) {
        supabase.postgrest.rpc(
            function = RPC_REGISTER_DEVICE_TOKEN,
            parameters = buildJsonObject {
                put("p_fcm_token", fcmToken)
                put("p_device_id", deviceId)
            },
        )
    }

    override suspend fun unregister(deviceId: String) {
        supabase.postgrest.rpc(
            function = RPC_UNREGISTER_DEVICE_TOKEN,
            parameters = buildJsonObject {
                put("p_device_id", deviceId)
            },
        )
    }

    private companion object {
        const val RPC_REGISTER_DEVICE_TOKEN = "register_device_token"
        const val RPC_UNREGISTER_DEVICE_TOKEN = "unregister_device_token"
    }
}
