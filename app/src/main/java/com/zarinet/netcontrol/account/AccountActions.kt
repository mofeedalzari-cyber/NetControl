package com.zarinet.netcontrol.account

import android.content.Context
import com.zarinet.netcontrol.data.DeviceStore
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.subscription.SubscriptionService

/** عمليات الحساب المشتركة بين الشاشات. */
object AccountActions {
    suspend fun logout(ctx: Context) {
        AuthService.signOut()
        SubscriptionService.reset()
        Session.device = null; Session.disconnect()
        DeviceStore(ctx).save(emptyList())
    }

    suspend fun changePassword(ctx: Context, current: String, newPassword: String) {
        val oldKey = AuthService.changePassword(current, newPassword)
        runCatching { CloudDevices(DeviceStore(ctx)).reencrypt(oldKey) }
    }
}
