package com.kodawick.messwise.data

import com.kodawick.messwise.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Error from the Messwise API. [code] is one of NOT_AUTH, FORBIDDEN, NOT_FOUND, INVALID, STATE, LIMIT, DUPLICATE, IMMUTABLE, NETWORK, SERVER. */
class ApiException(val code: String, message: String, val status: Int = 0) : Exception(message) {
    val isNetwork get() = code == "NETWORK"
}

object Http {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    const val base = BuildConfig.SUPABASE_URL
    const val anonKey = BuildConfig.SUPABASE_ANON_KEY

    suspend fun execute(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).await().use { r -> r.code to (r.body?.string() ?: "") }
        } catch (e: IOException) {
            throw ApiException("NETWORK", e.message ?: "network error")
        }
    }

    /** Converts a Supabase error body into an [ApiException] with the Messwise error code when there is one. */
    fun error(status: Int, body: String): ApiException {
        val obj = try { json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }
        val msg = listOf("message", "msg", "error_description", "error").firstNotNullOfOrNull { k ->
            (obj?.get(k)?.takeIf { it !is JsonNull })?.jsonPrimitive?.contentOrNull
        } ?: body.take(200)
        val m = Regex("^MESSWISE_([A-Z_]+):\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(msg)
        if (m != null) return ApiException(m.groupValues[1], m.groupValues[2].trim(), status)
        return when (status) {
            401 -> ApiException("NOT_AUTH", msg, status)
            403 -> ApiException("FORBIDDEN", msg, status)
            404 -> ApiException("NOT_FOUND", msg, status)
            400, 409, 422 -> ApiException("INVALID", msg, status)
            else -> ApiException("SERVER", msg, status)
        }
    }

    fun jsonBody(obj: JsonElement) = json.encodeToString(JsonElement.serializer(), obj).toRequestBody(JSON_TYPE)
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
    cont.invokeOnCancellation { try { cancel() } catch (_: Throwable) {} }
}
