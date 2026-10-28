package com.cryptosuite.simple

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Keystore-backed encrypted prefs (falls back to MODE_PRIVATE if Keystore is broken). */
class Store(ctx: Context) {
    private val p: SharedPreferences = try {
        val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(ctx, "secure_cfg", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    } catch (e: Exception) { ctx.getSharedPreferences("cfg_private", Context.MODE_PRIVATE) }

    var url: String get() = p.getString("u", "") ?: ""; set(v) { p.edit().putString("u", v).apply() }
    var edgeKey: String get() = p.getString("e", "") ?: ""; set(v) { p.edit().putString("e", v).apply() }
    var apiKey: String get() = p.getString("k", "") ?: ""; set(v) { p.edit().putString("k", v).apply() }
    var rootWarned: Boolean get() = p.getBoolean("rw", false); set(v) { p.edit().putBoolean("rw", v).apply() }
    val configured get() = url.isNotBlank()
    fun clear() = p.edit().clear().apply()
    fun api() = Api(url, edgeKey, apiKey)
}

object RootCheck {
    fun likelyRooted(): Boolean {
        val paths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/app/Superuser.apk",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su")
        return paths.any { java.io.File(it).exists() } || (android.os.Build.TAGS?.contains("test-keys") == true)
    }
}
