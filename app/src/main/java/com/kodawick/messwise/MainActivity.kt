package com.kodawick.messwise

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.kodawick.messwise.data.Graph
import com.kodawick.messwise.notify.Notify
import com.kodawick.messwise.ui.AppRoot

class MesswiseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notify.createChannels(this)
        if (Graph.session.signedIn && Graph.prefs.notificationsOn) Notify.schedule(this)
    }
}

class MainActivity : AppCompatActivity() {
    /** A join code from a shared link or QR (messwise://join/CODE), or a screen to open from a notification. */
    var pendingLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingLink = linkFrom(intent)
        setContent { AppRoot(activity = this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { pendingLink = it }
    }

    private fun linkFrom(intent: Intent?): String? {
        intent ?: return null
        intent.getStringExtra(EXTRA_ROUTE)?.let { return it }
        val data = intent.data ?: return null
        // https://messwise-r9qh.onrender.com/join?code=ABC123 (invite links and QR codes) or messwise://join/ABC123 (older QR codes)
        val code = data.getQueryParameter("code")
            ?: data.pathSegments.lastOrNull()?.takeIf { data.host == "join" || data.pathSegments.dropLast(1).lastOrNull() == "join" }
        return code?.let { "join?code=$it" }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
