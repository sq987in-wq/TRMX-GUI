package dev.trmx.gui.store

/*
 * Device-side secret + connection store. Phase 4 uses plain
 * MODE_PRIVATE SharedPreferences; Phase 10 (hardening) migrates the token
 * to EncryptedSharedPreferences / Keystore-wrapped storage. Honest scope
 * note recorded in ADR-006.
 */

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

class TokenStore(context: Context) {

    private val prefs = context.getSharedPreferences("trmx_store", Context.MODE_PRIVATE)

    /** Data-plane base URL — loopback only in V1 (Phase 0 decision). */
    val bridgeBaseUrl: String = "http://127.0.0.1:27342"

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var installBase: String
        get() = prefs.getString(KEY_BASE, DEFAULT_BASE) ?: DEFAULT_BASE
        set(value) = prefs.edit().putString(KEY_BASE, value).apply()

    val isPaired: Boolean get() = token.length >= 20

    fun clear() = prefs.edit().clear().apply()

    /** App-generated pairing token: 32 random bytes, url-safe base64 (~43 chars). */
    fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        private const val KEY_TOKEN = "pairing_token"
        private const val KEY_BASE = "install_base"
        private const val DEFAULT_BASE =
            "https://raw.githubusercontent.com/sq987in-wq/TRMX-GUI/main/termux"
    }
}
