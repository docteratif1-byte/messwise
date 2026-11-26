package com.kodawick.messwise.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Request

@Serializable
data class Tokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String,
    val email: String? = null,
)

/**
 * Keeps the Supabase sign-in. Tokens are encrypted with a key that never leaves the phone's
 * Android Keystore, and refreshed before they expire.
 */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("messwise_session", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _tokens = MutableStateFlow(load())
    val tokens: StateFlow<Tokens?> = _tokens

    val signedIn get() = _tokens.value != null
    val userId get() = _tokens.value?.userId

    fun save(t: Tokens?) {
        _tokens.value = t
        if (t == null) {
            prefs.edit().remove(KEY).apply()
        } else {
            prefs.edit().putString(KEY, encrypt(Http.json.encodeToString(Tokens.serializer(), t))).apply()
        }
    }

    /** A valid access token, refreshing it first when it is about to expire. */
    suspend fun accessToken(): String {
        val now = System.currentTimeMillis() / 1000
        val cur = _tokens.value ?: throw ApiException("NOT_AUTH", "signed out")
        if (cur.expiresAt - 90 > now) return cur.accessToken
        return mutex.withLock {
            val again = _tokens.value ?: throw ApiException("NOT_AUTH", "signed out")
            if (again.expiresAt - 90 > System.currentTimeMillis() / 1000) return@withLock again.accessToken
            refresh(again).accessToken
        }
    }

    suspend fun forceRefresh(): String = mutex.withLock {
        val cur = _tokens.value ?: throw ApiException("NOT_AUTH", "signed out")
        refresh(cur).accessToken
    }

    private suspend fun refresh(cur: Tokens): Tokens {
        val body = buildJsonObject { put("refresh_token", cur.refreshToken) }
        val req = Request.Builder().url("${Http.base}/auth/v1/token?grant_type=refresh_token")
            .header("apikey", Http.anonKey).post(Http.jsonBody(body)).build()
        val (code, text) = Http.execute(req)
        if (code in 200..299) {
            val t = parseTokens(text)
            save(t)
            return t
        }
        if (code == 400 || code == 401 || code == 403) {
            save(null)
            throw ApiException("NOT_AUTH", "session expired")
        }
        throw Http.error(code, text)
    }

    companion object {
        private const val KEY = "tokens"
        private const val ALIAS = "messwise_session_key"

        fun parseTokens(text: String): Tokens {
            val o: JsonObject = Http.json.parseToJsonElement(text).jsonObject
            val access = o["access_token"]!!.jsonPrimitive.content
            val refresh = o["refresh_token"]!!.jsonPrimitive.content
            val expiresIn = o["expires_in"]?.jsonPrimitive?.long ?: 3600L
            val expiresAt = o["expires_at"]?.jsonPrimitive?.long ?: (System.currentTimeMillis() / 1000 + expiresIn)
            val user = o["user"]?.jsonObject
            return Tokens(access, refresh, expiresAt, user?.get("id")?.jsonPrimitive?.content ?: "", user?.get("email")?.jsonPrimitive?.contentOrNull)
        }

        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            return gen.generateKey()
        }

        private fun encrypt(plain: String): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val iv = c.iv
            val enc = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
        }

        private fun decrypt(s: String): String {
            val (ivB, encB) = s.split(":", limit = 2)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivB, Base64.NO_WRAP)))
            return String(c.doFinal(Base64.decode(encB, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    }

    private fun load(): Tokens? = try {
        prefs.getString(KEY, null)?.let { Http.json.decodeFromString(Tokens.serializer(), decrypt(it)) }
    } catch (_: Exception) {
        prefs.edit().remove(KEY).apply()
        null
    }
}
