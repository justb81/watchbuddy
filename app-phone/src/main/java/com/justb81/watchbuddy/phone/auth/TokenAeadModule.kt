package com.justb81.watchbuddy.phone.auth

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the Tink AEAD primitive used by [TokenRepository].
 *
 * The AEAD keyset is persisted in a dedicated SharedPreferences file and itself
 * encrypted with a KEK that lives in the Android Keystore. Isolating this plumbing
 * in a Hilt provider keeps [TokenRepository] free of Keystore-specific init logic
 * so it can be unit-tested against a plain [Aead] mock.
 *
 * If the Keystore is unavailable (locked, hardware failure, compromised device) the
 * provider returns a [BrokenAead] sentinel instead of throwing, so the Hilt graph
 * still builds and the app can show a meaningful error before the first UI frame.
 * Any call to [BrokenAead.encrypt] or [BrokenAead.decrypt] throws
 * [AuthUnavailableException], which [TokenRepository] propagates to callers.
 */
@Module
@InstallIn(SingletonComponent::class)
object TokenAeadModule {

    private const val TAG = "TokenAeadModule"
    private const val KEYSET_PREFS_NAME = "watchbuddy_keyset_prefs"
    private const val KEYSET_NAME = "watchbuddy_aead_keyset"
    private const val KEYSTORE_URI = "android-keystore://watchbuddy_master_key"

    @Provides
    @Singleton
    fun provideTokenAead(@ApplicationContext context: Context): Aead {
        return try {
            DiagnosticLog.event(TAG, "registering Tink AEAD config")
            AeadConfig.register()
            DiagnosticLog.event(TAG, "opening Keystore-wrapped Tink keyset")
            AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS_NAME)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(KEYSTORE_URI)
                .build()
                .keysetHandle
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        } catch (e: Exception) {
            DiagnosticLog.error(TAG, "Keystore AEAD init failed — auth storage unavailable", e)
            BrokenAead(AuthUnavailableException("Keystore unavailable: ${e.message}", e))
        }
    }

    /**
     * Sentinel returned when the Keystore fails to initialise. Every operation
     * rethrows the original failure as [AuthUnavailableException] so the Hilt
     * graph builds and the UI can present a typed error instead of crashing.
     */
    private class BrokenAead(private val reason: AuthUnavailableException) : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = throw reason
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray = throw reason
    }
}
