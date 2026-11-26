package com.kodawick.messwise.data

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** App-wide objects, created once in [com.kodawick.messwise.MesswiseApp]. */
object Graph {
    lateinit var appContext: Context
        private set
    lateinit var session: Session
        private set
    lateinit var cache: Cache
        private set
    lateinit var api: Api
        private set
    lateinit var repo: Repo
        private set
    lateinit var prefs: Prefs
        private set

    /** Bumped after any change, so open screens reload what they show. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes
    fun changed() { _changes.value = _changes.value + 1 }

    fun init(context: Context) {
        appContext = context.applicationContext
        session = Session(appContext)
        cache = Cache(appContext)
        api = Api(session, cache)
        repo = Repo(api)
        prefs = Prefs(appContext)
    }
}

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("messwise_prefs", Context.MODE_PRIVATE)

    /** "system", "light" or "dark". */
    var theme: String
        get() = p.getString("theme", "system") ?: "system"
        set(v) = p.edit().putString("theme", v).apply()

    var appLock: Boolean
        get() = p.getBoolean("app_lock", false)
        set(v) = p.edit().putBoolean("app_lock", v).apply()

    var notificationsOn: Boolean
        get() = p.getBoolean("notifications", true)
        set(v) = p.edit().putBoolean("notifications", v).apply()

    var lastNotifiedId: Long
        get() = p.getLong("last_notified_id", 0)
        set(v) = p.edit().putLong("last_notified_id", v).apply()

    /** The office or hostel shown when the app opens. */
    var lastPlaceId: String?
        get() = p.getString("last_place", null)
        set(v) = p.edit().putString("last_place", v).apply()

    var googleClientId: String?
        get() = p.getString("google_client_id", null)
        set(v) = p.edit().putString("google_client_id", v).apply()

    /** "en", "ur" or "" (phone language). Applied through AppCompat so it also works on Android 12 and older. */
    var language: String
        get() = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-').substringBefore(',')
        set(v) = AppCompatDelegate.setApplicationLocales(if (v.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(v))
}

/** Google sign-in through Android's Credential Manager, then a Messwise session on Supabase. */
object GoogleAuth {
    class NotConfigured : Exception("Google sign-in is not set up yet")
    class Cancelled : Exception("cancelled")

    suspend fun clientId(): String? = Graph.prefs.googleClientId?.takeIf { it.isNotBlank() } ?: fetchClientId()

    suspend fun fetchClientId(): String? = try {
        val cfg = Graph.api.appConfig()
        val id = cfg["google_web_client_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        Graph.prefs.googleClientId = id
        id
    } catch (_: Exception) { Graph.prefs.googleClientId }

    suspend fun signIn(activity: Activity) {
        val clientId = clientId() ?: throw NotConfigured()
        val rawNonce = Api.randomHex(16)
        val option = GetSignInWithGoogleOption.Builder(clientId).setNonce(Api.sha256(rawNonce)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = try {
            CredentialManager.create(activity).getCredential(activity, request)
        } catch (e: GetCredentialCancellationException) {
            throw Cancelled()
        } catch (e: NoCredentialException) {
            throw ApiException("NOT_AUTH", "No Google account on this phone. Add one in phone Settings.")
        } catch (e: GetCredentialException) {
            val fresh = fetchClientId()
            if (fresh != null && fresh != clientId) return signIn(activity)
            throw ApiException("NOT_AUTH", e.message ?: "Google sign-in failed")
        }
        val cred = result.credential
        if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val google = GoogleIdTokenCredential.createFrom(cred.data)
            Graph.api.signInWithGoogle(google.idToken, rawNonce)
        } else {
            throw ApiException("NOT_AUTH", "Unexpected sign-in result")
        }
    }
}
