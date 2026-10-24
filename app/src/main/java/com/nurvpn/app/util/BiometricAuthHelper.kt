package com.nurvpn.app.util

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricAuthHelper {

    private const val PREFS_NAME = "biometric_prefs"
    private const val KEY_LAST_UNLOCK = "last_unlock_time"
    private const val SESSION_TIMEOUT_MS = 5 * 60 * 1000L // 5 daqiqa

    /** Seans faolmi? (5 daqiqa o'tmaganmi?) */
    fun isSessionActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastUnlock = prefs.getLong(KEY_LAST_UNLOCK, 0)
        return (System.currentTimeMillis() - lastUnlock) < SESSION_TIMEOUT_MS
    }

    /** Seansni yangilash (muvaffaqiyatli kirgandan keyin) */
    fun setSessionActive(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_UNLOCK, System.currentTimeMillis()).apply()
    }

    /** Seansni yopish (qulflash) */
    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_LAST_UNLOCK).apply()
    }

    /**
     * Biometrik autentifikatsiya oynasini ochish.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = activity.getString(com.nurvpn.app.R.string.bio_title),
        subtitle: String = activity.getString(com.nurvpn.app.R.string.bio_sub_default),
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    setSessionActive(activity)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Barmoq noto'g'ri bosilganda (dialog yopilmaydi)
                }
            })

        val canAuthenticate = BiometricManager.from(activity).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or 
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            // Agar telefonda parol, PIN yoki barmoq izi umuman bo'lmasa:
            // Foydalanuvchini bloklab qo'ymaymiz, faqat ogohlantiramiz.
            android.widget.Toast.makeText(
                activity,
                activity.getString(com.nurvpn.app.R.string.bio_no_lock),
                android.widget.Toast.LENGTH_LONG
            ).show()
            
            onSuccess() // Ruxsat beramiz
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or 
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
