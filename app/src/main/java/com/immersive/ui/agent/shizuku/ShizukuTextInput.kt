package com.immersive.ui.agent.shizuku

import android.util.Base64
import kotlinx.coroutines.delay

/**
 * Privileged text entry for apps whose input field is NOT in the accessibility tree
 * (e.g. WeChat), where the normal `type_text` (ACTION_SET_TEXT on a focused accessible
 * node) cannot reach.
 *
 * Uses ADBKeyboard (a tiny IME that accepts broadcast input): via Shizuku it temporarily
 * switches the default IME to ADBKeyboard, broadcasts the text (base64 so Chinese/quotes
 * survive the shell), then restores the user's previous IME. ADBKeyboard commits text
 * through the InputConnection like a real keyboard, so the field need not be accessible.
 */
object ShizukuTextInput {

    private const val IME_ID = "com.android.adbkeyboard/.AdbIME"
    private const val PKG = "com.android.adbkeyboard"

    /** True when privileged text entry is possible (Shizuku ready + ADBKeyboard installed). */
    suspend fun isAvailable(): Boolean {
        if (!ShizukuScreencap.isAvailable()) return false // same Shizuku ready check
        val res = ShizukuManager.exec("pm list packages $PKG")
        return res.ok && res.output.contains(PKG)
    }

    /**
     * Type [text] (including Chinese) into the currently focused field. Returns false when
     * Shizuku/ADBKeyboard is unavailable or a step failed; the caller then reports that
     * typing could not be performed.
     */
    suspend fun type(text: String): Boolean {
        if (text.isEmpty() || !isAvailable()) return false

        // Remember the user's current IME so we can restore it afterward.
        val prevIme = ShizukuManager.exec("settings get secure default_input_method")
            .output.lineSequence().map { it.trim() }.firstOrNull { it.contains("/") }

        try {
            ShizukuManager.exec("ime enable $IME_ID")
            if (!ShizukuManager.exec("ime set $IME_ID").ok) return false
            // Let the IME swap bind its InputConnection to the focused field.
            delay(450)
            // Broadcast as base64 (ASCII-safe through the shell; ADBKeyboard decodes UTF-8).
            val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val typed = ShizukuManager.exec("am broadcast -a ADB_INPUT_B64 --es msg $b64")
            delay(200)
            return typed.ok
        } finally {
            // Always restore the previous IME so the user's keyboard is not left swapped.
            if (!prevIme.isNullOrBlank() && prevIme != IME_ID) {
                ShizukuManager.exec("ime set $prevIme")
            }
        }
    }
}
