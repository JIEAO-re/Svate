package com.immersive.ui.agent.loop

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-backed [EncryptedSharedPreferences] factory shared by the on-device
 * settings stores (model endpoint, search endpoint). Returns null if the encrypted
 * store cannot be created on a device (e.g. a broken Keystore) so callers can fall
 * back to plaintext rather than crash. Secrets stored through it are AES256-GCM
 * encrypted at rest; the file name groups related keys into one store.
 */
internal object SecurePrefs {
    fun open(context: Context, fileName: String): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Throwable) {
        null
    }
}
