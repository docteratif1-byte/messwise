package com.kodawick.messwise.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Talks to the Messwise backend on Supabase: sign-in, the checked database functions (mw_*),
 * and the private "messwise" storage bucket for receipts and payment slips.
 */
class Api(private val session: Session, private val cache: Cache) {

    // ---------------------------------------------------------------- auth

    suspend fun signInWithGoogle(idToken: String, rawNonce: String?) {
        val body = buildJsonObject {
            put("provider", "google")
            put("id_token", idToken)
            if (rawNonce != null) put("nonce", rawNonce)
        }
        authToken("id_token", body)
    }

    /** Email test sign-in, only offered in test builds for the QA accounts. */
    suspend fun signInWithPassword(email: String, password: String) {
        authToken("password", buildJsonObject { put("email", email.trim()); put("password", password) })
    }

    private suspend fun authToken(grant: String, body: JsonObject) {
        val req = Request.Builder().url("${Http.base}/auth/v1/token?grant_type=$grant")
            .header("apikey", Http.anonKey).post(Http.jsonBody(body)).build()
        val (code, text) = Http.execute(req)
        if (code !in 200..299) throw Http.error(code, text)
        session.save(Session.parseTokens(text))
    }

    suspend fun signOut() {
        val token = session.tokens.value?.accessToken
        if (token != null) {
            try {
                Http.execute(Request.Builder().url("${Http.base}/auth/v1/logout").header("apikey", Http.anonKey)
                    .header("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build())
            } catch (_: Exception) {}
        }
        session.save(null)
        cache.clear()
    }

    /** Public settings readable before sign-in (Google client id, minimum app version, support email). */
    suspend fun appConfig(): JsonObject {
        val req = Request.Builder().url("${Http.base}/rest/v1/rpc/mw_app_config")
            .header("apikey", Http.anonKey).header("Authorization", "Bearer ${Http.anonKey}")
            .post(Http.jsonBody(JsonObject(emptyMap()))).build()
        val (code, text) = Http.execute(req)
        if (code !in 200..299) throw Http.error(code, text)
        return Http.json.parseToJsonElement(text).jsonObject
    }

    // ---------------------------------------------------------------- RPC

    suspend fun rpcRaw(fn: String, args: JsonObject): JsonElement {
        var token = session.accessToken()
        repeat(2) { attempt ->
            val req = Request.Builder().url("${Http.base}/rest/v1/rpc/$fn")
                .header("apikey", Http.anonKey)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .post(Http.jsonBody(args)).build()
            val (code, text) = Http.execute(req)
            if (code in 200..299) return if (text.isBlank()) JsonNull else Http.json.parseToJsonElement(text)
            if (code == 401 && attempt == 0) {
                token = session.forceRefresh()
                return@repeat
            }
            val err = Http.error(code, text)
            if (err.code == "NOT_AUTH" && code == 401) session.save(null)
            throw err
        }
        throw ApiException("NOT_AUTH", "signed out")
    }

    /** Calls a function and decodes the result. With [cacheKey], a good answer is kept for offline viewing. */
    suspend fun <T> rpc(fn: String, args: JsonObject, serializer: KSerializer<T>, cacheKey: String? = null): T {
        val raw = rpcRaw(fn, args)
        if (cacheKey != null) cache.put(cacheKey, raw)
        return Http.json.decodeFromJsonElement(serializer, raw)
    }

    fun <T> cached(cacheKey: String, serializer: KSerializer<T>): T? = try {
        cache.get(cacheKey)?.let { Http.json.decodeFromJsonElement(serializer, it) }
    } catch (_: Exception) { null }

    // ---------------------------------------------------------------- storage

    /**
     * Uploads a photo into the caller's own folder and returns its storage path.
     * [folder]: "receipts" or "slips" inside a place, "logo" for the owner, or null for the private book.
     */
    suspend fun upload(placeId: String?, folder: String?, bytes: ByteArray, mime: String = "image/jpeg"): String {
        val uid = session.userId ?: throw ApiException("NOT_AUTH", "signed out")
        val ext = when (mime) { "image/png" -> "png"; "application/pdf" -> "pdf"; else -> "jpg" }
        val path = if (placeId == null || folder == null) "personal/$uid/${randomHex(12)}.$ext" else "$placeId/$folder/$uid/${randomHex(12)}.$ext"
        val req = Request.Builder().url("${Http.base}/storage/v1/object/messwise/$path")
            .header("apikey", Http.anonKey)
            .header("Authorization", "Bearer ${session.accessToken()}")
            .header("x-upsert", "false")
            .header("cache-control", "3600")
            .post(bytes.toRequestBody(mime.toMediaType())).build()
        val (code, text) = Http.execute(req)
        if (code !in 200..299) throw Http.error(code, text)
        return path
    }

    /** A short-lived link to view a stored photo (only works for people allowed to see it). */
    suspend fun signedUrl(path: String, seconds: Int = 600): String {
        val req = Request.Builder().url("${Http.base}/storage/v1/object/sign/messwise/$path")
            .header("apikey", Http.anonKey)
            .header("Authorization", "Bearer ${session.accessToken()}")
            .post(Http.jsonBody(buildJsonObject { put("expiresIn", seconds) })).build()
        val (code, text) = Http.execute(req)
        if (code !in 200..299) throw Http.error(code, text)
        val signed = Http.json.parseToJsonElement(text).jsonObject["signedURL"]?.jsonPrimitive?.contentOrNull
            ?: throw ApiException("SERVER", "no link returned")
        return if (signed.startsWith("http")) signed else "${Http.base}/storage/v1$signed"
    }

    companion object {
        private val rnd = SecureRandom()
        fun randomHex(bytes: Int): String {
            val b = ByteArray(bytes)
            rnd.nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Last good answers per screen, so the dashboard and bills can be viewed without internet. */
class Cache(context: Context) {
    private val dir = File(context.filesDir, "cache_v1").apply { mkdirs() }

    private fun file(key: String) = File(dir, Api.sha256(key) + ".json")

    suspend fun put(key: String, value: JsonElement) = withContext(Dispatchers.IO) {
        try { file(key).writeText(Http.json.encodeToString(JsonElement.serializer(), value)) } catch (_: Exception) {}
    }

    fun get(key: String): JsonElement? = try {
        file(key).takeIf { it.exists() }?.readText()?.let { Http.json.parseToJsonElement(it) }
    } catch (_: Exception) { null }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }
}
