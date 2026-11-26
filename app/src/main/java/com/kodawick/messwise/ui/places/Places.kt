package com.kodawick.messwise.ui.places

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.kodawick.messwise.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.kodawick.messwise.ui.common.LocalFeedback
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstall
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.CircularProgressIndicator
import com.kodawick.messwise.data.Graph
import com.kodawick.messwise.data.model.JoinResult
import com.kodawick.messwise.ui.Nav
import com.kodawick.messwise.ui.auth.KodaWickCredit
import com.kodawick.messwise.ui.common.AppScreen
import com.kodawick.messwise.ui.common.Avatar
import com.kodawick.messwise.ui.common.ChoiceRow
import com.kodawick.messwise.ui.common.EmptyState
import com.kodawick.messwise.ui.common.Hint
import com.kodawick.messwise.ui.common.Loaded
import com.kodawick.messwise.ui.common.PrimaryButton
import com.kodawick.messwise.ui.common.ScrollColumn
import com.kodawick.messwise.ui.common.SecondaryButton
import com.kodawick.messwise.ui.common.SectionCard
import com.kodawick.messwise.ui.common.SmallLabel
import com.kodawick.messwise.ui.common.StatusChip
import com.kodawick.messwise.ui.common.TextInput
import com.kodawick.messwise.ui.common.money
import com.kodawick.messwise.ui.common.rememberAction
import com.kodawick.messwise.ui.common.rememberLoad
import com.kodawick.messwise.ui.common.roleLabel
import com.kodawick.messwise.ui.common.typeLabel
import com.kodawick.messwise.ui.openPlace
import com.kodawick.messwise.ui.theme.Brand
import com.kodawick.messwise.ui.theme.LocalStatus

const val PLAY_LINK = "https://play.google.com/store/apps/details?id=com.kodawick.messwise"

const val JOIN_LINK = "https://messwise-r9qh.onrender.com/join"

/** The link in invite QR codes and messages. Opens MessWise (or its web page with the Play link) on any phone camera. */
fun joinLink(code: String) = "$JOIN_LINK?code=" + code.uppercase().filter { it.isLetterOrDigit() }

/**
 * Finds the 6-letter join code in whatever the person typed, pasted or scanned: the code itself (with or without
 * the dash), a whole invite message, a join link (…/join?code=ABC123) or an older QR code (messwise://join/ABC123).
 */
fun extractJoinCode(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val up = raw.uppercase()
    val part = when {
        "CODE=" in up -> up.substringAfter("CODE=").substringBefore('&')
        "/JOIN/" in up -> up.substringAfter("/JOIN/")
        "CODE" in up -> up.substringAfterLast("CODE")
        "کوڈ" in raw -> raw.substringAfterLast("کوڈ").uppercase()
        else -> up
    }
    Regex("(?<![A-Z0-9])([A-Z0-9]{3})-?([A-Z0-9]{3})(?![A-Z0-9])").find(part)?.let { return it.groupValues[1] + it.groupValues[2] }
    val plain = part.filter { it in 'A'..'Z' || it in '0'..'9' }
    return plain.takeIf { it.length == 6 }
}

fun prettyCode(c: String) = c.uppercase().filter { it.isLetterOrDigit() }.let { if (it.length == 6) it.take(3) + "-" + it.drop(3) else it }

fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    val ink = AColor.rgb(74, 37, 69)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) ink else AColor.WHITE)
    return bmp
}

/** All offices and hostels this person belongs to, plus requests still waiting. */
@Composable
fun PlacesScreen(nav: Nav) {
    val h = rememberLoad("me", cached = { Graph.repo.meCached() }) { Graph.repo.me() }
    AppScreen(stringResource(R.string.my_places), onBack = null, actions = {
        IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
    }) { pad ->
        // A waiting join request: look again every 10 seconds so the place appears as soon as it is approved.
        LaunchedEffect(h.data?.pendingRequests?.size ?: 0) {
            while ((h.data?.pendingRequests?.size ?: 0) > 0) { delay(10_000); h.refresh() }
        }
        Loaded(h, Modifier) { me ->
            ScrollColumn(pad) {
                if (me.places.isEmpty() && me.pendingRequests.isEmpty()) {
                    EmptyState(Icons.Filled.Home, stringResource(R.string.no_places_title), stringResource(R.string.no_places_body))
                }
                me.places.forEach { p ->
                    SectionCard(onClick = { openPlace(nav, p.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(p.name, 44, MaterialTheme.colorScheme.secondaryContainer)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                SmallLabel(roleLabel(p.role, p.isMainManager) + " · " + typeLabel(p.type))
                            }
                            if (p.pendingPaisa > 0) Text(money(p.pendingPaisa), color = LocalStatus.current.owe, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                me.pendingRequests.forEach { r ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.HourglassTop, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.placeName, style = MaterialTheme.typography.titleMedium)
                                SmallLabel(stringResource(R.string.request_waiting))
                            }
                        }
                    }
                }
                PrimaryButton(stringResource(R.string.join_place), icon = Icons.Filled.GroupAdd, onClick = { nav.navigate("join") }, modifier = Modifier.testTag("go_join"))
                SecondaryButton(stringResource(R.string.create_place), icon = Icons.Filled.Add, onClick = { nav.navigate("create") }, modifier = Modifier.testTag("go_create"))
                Hint(stringResource(R.string.places_hint))
                KodaWickCredit()
            }
        }
    }
}

@Composable
fun CreatePlaceScreen(onBack: () -> Unit, onCreated: (String) -> Unit) {
    val me = remember { Graph.repo.meCached() }
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("hostel") }
    var myName by rememberSaveable { mutableStateOf(me?.profile?.fullName ?: "") }
    var type by rememberSaveable { mutableStateOf("resident") }
    val action = rememberAction()
    AppScreen(stringResource(R.string.create_place), onBack = onBack) { pad ->
        ScrollColumn(pad) {
            TextInput(name, { name = it.take(80) }, stringResource(R.string.place_name), supporting = stringResource(R.string.place_name_hint),
                modifier = Modifier.testTag("place_name"))
            SmallLabel(stringResource(R.string.place_kind))
            ChoiceRow(listOf("hostel" to stringResource(R.string.kind_hostel), "office" to stringResource(R.string.kind_office),
                "flat" to stringResource(R.string.kind_flat), "other" to stringResource(R.string.kind_other)), kind, { kind = it })
            TextInput(address, { address = it.take(160) }, stringResource(R.string.address_optional))
            TextInput(myName, { myName = it.take(60) }, stringResource(R.string.your_name_here))
            SmallLabel(stringResource(R.string.you_are))
            TypeChoice(type) { type = it }
            Hint(stringResource(R.string.owner_note))
            PrimaryButton(stringResource(R.string.create), enabled = name.trim().length >= 2 && myName.trim().length >= 2, busy = action.busy,
                modifier = Modifier.testTag("create_place"), onClick = {
                    action.run { val p = Graph.repo.placeCreate(name.trim(), address.trim(), kind, myName.trim(), type); onCreated(p.id) }
                })
        }
    }
}

/** Resident (tea + kitchen), Day visitor (tea, meals they eat), Kitchen only. */
@Composable
fun TypeChoice(type: String, onChange: (String) -> Unit) {
    ChoiceRow(listOf("resident" to stringResource(R.string.type_resident), "day" to stringResource(R.string.type_day),
        "kitchen" to stringResource(R.string.type_kitchen)), type, onChange)
    Hint(when (type) {
        "day" -> stringResource(R.string.type_day_hint)
        "kitchen" -> stringResource(R.string.type_kitchen_hint)
        else -> stringResource(R.string.type_resident_hint)
    })
}

@Composable
fun JoinScreen(initialCode: String?, onBack: () -> Unit, onMember: (String) -> Unit) {
    val ctx = LocalContext.current
    val snack = LocalFeedback.current?.snackbar
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf(extractJoinCode(initialCode)?.let { prettyCode(it) } ?: "") }
    var wrong by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JoinResult?>(null) }
    var sentCode by rememberSaveable { mutableStateOf<String?>(null) }
    val action = rememberAction()
    fun say(msg: String) { scope.launch { snack?.showSnackbar(msg) } }
    fun join(raw: String) {
        val c = extractJoinCode(raw)
        if (c == null) { wrong = true; return }
        wrong = false
        code = prettyCode(c)
        action.run {
            val r = Graph.repo.join(c)
            result = r
            if (r.status == "member") onMember(r.placeId) else sentCode = c
        }
    }
    // A code that came from an invite link or QR is sent straight away.
    LaunchedEffect(Unit) { if (extractJoinCode(initialCode) != null && result == null) join(initialCode!!) }
    // While the request waits, check every few seconds and open the place as soon as the manager approves.
    LaunchedEffect(sentCode) {
        val c = sentCode ?: return@LaunchedEffect
        repeat(120) {
            delay(8_000)
            try {
                val r = Graph.repo.join(c)
                result = r
                if (r.status == "member") { Graph.changed(); onMember(r.placeId); return@LaunchedEffect }
            } catch (_: Exception) {}
        }
    }
    AppScreen(stringResource(R.string.join_place), onBack = onBack) { pad ->
        ScrollColumn(pad) {
            val r = result
            if (r != null && r.status == "requested") {
                SectionCard(stringResource(R.string.request_sent_title)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.request_sent_body, r.placeName), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                    Hint(stringResource(R.string.request_sent_hint))
                    SecondaryButton(stringResource(R.string.back_to_places), onClick = onBack)
                }
                return@ScrollColumn
            }
            Text(stringResource(R.string.join_body), style = MaterialTheme.typography.bodyLarge)
            TextInput(code, { v ->
                wrong = false
                code = extractJoinCode(v)?.takeIf { v.length > 7 }?.let { prettyCode(it) } ?: v.uppercase().take(60)
            }, stringResource(R.string.join_code), modifier = Modifier.testTag("join_code"),
                isError = wrong, supporting = if (wrong) stringResource(R.string.join_code_wrong) else null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.scan_qr), icon = Icons.Filled.QrCodeScanner, modifier = Modifier.weight(1f), onClick = {
                    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                    val scanner = GmsBarcodeScanning.getClient(ctx, options)
                    scanner.startScan()
                        .addOnSuccessListener { b ->
                            val c = extractJoinCode(b.rawValue)
                            if (c == null) say(ctx.getString(R.string.qr_not_invite)) else join(c)
                        }
                        .addOnFailureListener {
                            // Usually the scanner part of Google Play services is still downloading.
                            ModuleInstall.getClient(ctx).installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
                            say(ctx.getString(R.string.scanner_not_ready))
                        }
                })
                PrimaryButton(stringResource(R.string.join), busy = action.busy, modifier = Modifier.weight(1f).testTag("join_go"), onClick = { join(code) })
            }
        }
    }
}

/** Join code as text and QR, ready to send on WhatsApp. Managers can make a new code. */
@Composable
fun InviteScreen(placeId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val h = rememberLoad("place", placeId, cached = { Graph.repo.placeCached(placeId) }) { Graph.repo.place(placeId) }
    val action = rememberAction()
    AppScreen(stringResource(R.string.invite_members), onBack = onBack) { pad ->
        Loaded(h) { p ->
            ScrollColumn(pad) {
                val code = p.joinCode
                if (code == null) {
                    Hint(stringResource(R.string.only_managers_invite))
                } else {
                    val text = ctx.getString(R.string.share_join_text, p.name, prettyCode(code), joinLink(code))
                    SectionCard {
                        val bmp = remember(code) { qrBitmap(joinLink(code)) }
                        Image(bmp.asImageBitmap(), stringResource(R.string.qr_code), Modifier.size(220.dp).align(Alignment.CenterHorizontally))
                        Text(prettyCode(code), fontFamily = FontFamily.Monospace, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Brand.Aubergine,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("invite_code"))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton(stringResource(R.string.copy), icon = Icons.Filled.ContentCopy, modifier = Modifier.weight(1f),
                                onClick = { clip.setText(AnnotatedString(prettyCode(code))) })
                            SecondaryButton(stringResource(R.string.share), icon = Icons.AutoMirrored.Filled.Send, modifier = Modifier.weight(1f), onClick = {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                            })
                        }
                        PrimaryButton(stringResource(R.string.send_whatsapp), onClick = {
                            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=" + Uri.encode(text)))
                            try { ctx.startActivity(i) } catch (_: Exception) {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                            }
                        })
                    }
                    Hint(stringResource(R.string.invite_hint))
                    TextButton(onClick = { action.run(success = ctx.getString(R.string.new_code_made)) { Graph.repo.newCode(placeId); h.refresh() } }) {
                        Text(stringResource(R.string.make_new_code))
                    }
                }
            }
        }
    }
}
