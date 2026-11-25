package com.safespace.guard

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Settings, parent PIN and blocked-attempt statistics. All data stays on the device. */
object Store {

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("safespace", Context.MODE_PRIVATE)

    // ---- Settings ----

    fun isProtectionEnabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setProtectionEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun isStrict(c: Context) = sp(c).getBoolean("strict", true)
    fun setStrict(c: Context, v: Boolean) = sp(c).edit().putBoolean("strict", v).apply()

    // ---- Parent PIN (salted PBKDF2, never stored in plain text) ----

    fun hasPin(c: Context) = sp(c).contains("pin_hash")

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp(c).edit()
            .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .apply()
    }

    fun checkPin(c: Context, pin: String): Boolean {
        val s = sp(c)
        val salt = s.getString("pin_salt", null) ?: return false
        val expected = s.getString("pin_hash", null) ?: return false
        val actual = hash(pin, Base64.decode(salt, Base64.NO_WRAP))
        return MessageDigest.isEqual(Base64.decode(expected, Base64.NO_WRAP), actual)
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 60_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    // ---- Statistics ----

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    @Synchronized
    fun recordBlocked(c: Context, domain: String) {
        val s = sp(c)
        val day = today()
        val todayCount = if (s.getString("day", "") == day) s.getInt("today", 0) else 0
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        val recent = (listOf("$time  $domain") + recentList(c)).take(15)
        s.edit()
            .putString("day", day)
            .putInt("today", todayCount + 1)
            .putInt("total", s.getInt("total", 0) + 1)
            .putString("recent", recent.joinToString("\n"))
            .apply()
    }

    fun blockedToday(c: Context): Int {
        val s = sp(c)
        return if (s.getString("day", "") == today()) s.getInt("today", 0) else 0
    }

    fun blockedTotal(c: Context) = sp(c).getInt("total", 0)

    fun recentList(c: Context): List<String> {
        val raw = sp(c).getString("recent", "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split("\n")
    }
}
