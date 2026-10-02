package com.autonomousone.messages.gateway

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.provider.Telephony

/**
 * Whether this app currently holds the platform SMS role — the ONE definition.
 *
 * The app had three copies of this question (`MainActivity`, `GatewayServer`, `SmsReceiver`) and
 * they did not agree: only one of them consulted `RoleManager` on Android 10+, so a device where the
 * role had been moved to another app could still be told "default SMS app" by the SMS receiver's
 * copy. Telemetry reporting a `defaultSmsRole` flag has to be the same fact the send path acts on,
 * or the report is worse than absent.
 *
 * A denied role and an exception both answer `false` — the app cannot act as the SMS handler, which
 * is the only thing callers may conclude.
 */
object DefaultSmsRole {

    fun isHeld(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }
    } catch (e: Exception) {
        false
    }
}
