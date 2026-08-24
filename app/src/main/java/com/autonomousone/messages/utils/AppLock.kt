package com.autonomousone.messages.utils

import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.SharedPreferences
/**
 * Optional app lock via device biometrics (fingerprint / face) with the
 * device credential (PIN/pattern) as fallback — exactly what
 * [BiometricPrompt.BIOMETRIC_WEAK | DEVICE_CREDENTIAL] gives us.
 *
 * State is one boolean in SharedPreferences; the lock gate lives in
 * MainActivity so every screen is protected.
 */
class AppLockPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "app_lock_prefs"
        private const val KEY_ENABLED = "lock_enabled"
    }

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()
}

/** True when this device has a usable biometric/credential authenticator. */
fun isBiometricAvailable(context: Context): Boolean {
    val manager = BiometricManager.from(context)
    return manager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
    ) == BiometricManager.BIOMETRIC_SUCCESS
}

/**
 * Shows the system biometric prompt on [activity]; calls [onSuccess] /
 * [onError] on the main thread. No crypto — pure gate.
 */
fun showBiometricPrompt(
    activity: AppCompatActivity,
    title: String = "Unlock Messages",
    subtitle: String = "Confirm it's you to continue",
    onSuccess: () -> Unit,
    onError: () -> Unit
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // ERROR_USER_CANCELED etc. — treat any error as "locked".
                onError()
            }
        }
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build()
    prompt.authenticate(info)
}
