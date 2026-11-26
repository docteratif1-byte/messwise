package com.kodawick.messwise.ui.auth

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.EmojiFoodBeverage
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodawick.messwise.BuildConfig
import com.kodawick.messwise.MainActivity
import com.kodawick.messwise.R
import com.kodawick.messwise.data.Graph
import com.kodawick.messwise.data.GoogleAuth
import com.kodawick.messwise.notify.Notify
import com.kodawick.messwise.ui.common.AppScreen
import com.kodawick.messwise.ui.common.Hint
import com.kodawick.messwise.ui.common.LocalFeedback
import com.kodawick.messwise.ui.common.PrimaryButton
import com.kodawick.messwise.ui.common.ScrollColumn
import com.kodawick.messwise.ui.common.TextInput
import com.kodawick.messwise.ui.common.plainError
import com.kodawick.messwise.ui.common.rememberAction
import com.kodawick.messwise.ui.theme.Brand
import kotlinx.coroutines.launch

const val SUPPORT_EMAIL = "kodawick4+messwise@gmail.com"

/** The Messwise cup on its aubergine tile. */
@Composable
fun BrandMark(size: Int) {
    Box(Modifier.size(size.dp).background(Brand.Aubergine, RoundedCornerShape((size / 4).dp)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size((size * 1.45).dp))
    }
}

@Composable
fun KodaWickCredit() {
    Text(stringResource(R.string.by_kodawick), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
}

fun emailSupport(ctx: android.content.Context, subject: String) {
    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL")).putExtra(Intent.EXTRA_SUBJECT, subject)
    try { ctx.startActivity(i) } catch (_: Exception) {}
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun LoginScreen(activity: MainActivity, onSignedIn: (needsName: Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalFeedback.current!!.snackbar
    var busy by remember { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showQa by rememberSaveable { mutableStateOf(false) }

    fun signIn(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            val message: String? = try {
                block()
                val me = Graph.repo.me()
                if (Graph.prefs.notificationsOn) Notify.schedule(ctx)
                Graph.changed()
                busy = false
                onSignedIn(me.profile.fullName.isBlank())
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: GoogleAuth.Cancelled) {
                null
            } catch (_: GoogleAuth.NotConfigured) {
                ctx.getString(R.string.google_not_ready)
            } catch (e: Exception) {
                plainError(ctx, e)
            } finally {
                busy = false
            }
            if (message != null) snack.showSnackbar(message)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(36.dp))
            BrandMark(100)
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.app_name), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Column(Modifier.fillMaxWidth()) {
                Feature(Icons.Filled.EmojiFoodBeverage, stringResource(R.string.feature_tea))
                Feature(Icons.AutoMirrored.Filled.ReceiptLong, stringResource(R.string.feature_kitchen))
                Feature(Icons.Filled.Lock, stringResource(R.string.feature_private))
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { signIn { GoogleAuth.signIn(activity) } },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(54.dp).testTag("google_sign_in"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else {
                    Image(painterResource(R.drawable.ic_google), null, Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.continue_google), fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(14.dp))
            Hint(stringResource(R.string.login_note))
            if (BuildConfig.QA_LOGIN) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { showQa = !showQa }, modifier = Modifier.testTag("qa_toggle")) { Text(stringResource(R.string.qa_login)) }
                if (showQa) {
                    OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.email)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().testTag("qa_email"))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("qa_password"))
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton(stringResource(R.string.sign_in), onClick = { signIn { Graph.api.signInWithPassword(email.trim(), password) } },
                        busy = busy, modifier = Modifier.testTag("qa_submit"))
                }
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = { emailSupport(ctx, "Messwise help") }) { Text(stringResource(R.string.help_support)) }
            KodaWickCredit()
        }
    }
}

/** First sign-in: the name the group will see. */
@Composable
fun ProfileSetupScreen(onDone: () -> Unit) {
    val me = remember { Graph.repo.meCached() }
    var name by rememberSaveable { mutableStateOf(me?.profile?.fullName ?: "") }
    val action = rememberAction()
    AppScreen(stringResource(R.string.setup_title), onBack = null) { pad ->
        ScrollColumn(pad) {
            Text(stringResource(R.string.setup_body), style = MaterialTheme.typography.bodyLarge)
            TextInput(name, { name = it.take(60) }, stringResource(R.string.full_name), modifier = Modifier.testTag("setup_name"))
            PrimaryButton(stringResource(R.string.continue_btn), enabled = name.trim().length >= 2, busy = action.busy, onClick = {
                action.run(after = { onDone() }) { Graph.repo.profileUpdate(name.trim(), null) }
            }, modifier = Modifier.testTag("setup_continue"))
        }
    }
}
