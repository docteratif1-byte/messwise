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
    var code by rememberSaveable { mutableStateOf(initialCode?.let { prettyCode(it) } ?: "") }
    var result by remember { mutableStateOf<JoinResult?>(null) }
    val action = rememberAction()
    fun join(c: String) = action.run {
        val r = Graph.repo.join(c.filter { it.isLetterOrDigit() }.uppercase())
        result = r
        if (r.status == "member") onMember(r.placeId)
    }
    AppScreen(stringResource(R.string.join_place), onBack = onBack) { pad ->
        ScrollColumn(pad) {
            Text(stringResource(R.string.join_body), style = MaterialTheme.typography.bodyLarge)
            TextInput(code, { code = it.uppercase().take(8) }, stringResource(R.string.join_code), modifier = Modifier.testTag("join_code"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.scan_qr), icon = Icons.Filled.QrCodeScanner, modifier = Modifier.weight(1f), onClick = {
                    GmsBarcodeScanning.getClient(ctx).startScan().addOnSuccessListener { b ->
                        val raw = b.rawValue ?: return@addOnSuccessListener
                        val c = raw.substringAfterLast("/").substringAfter("code=")
                        code = prettyCode(c)
                        join(c)
                    }
                })
                PrimaryButton(stringResource(R.string.join), busy = action.busy, enabled = code.count { it.isLetterOrDigit() } == 6,
                    modifier = Modifier.weight(1f).testTag("join_go"), onClick = { join(code) })
            }
            val r = result
            if (r != null && r.status == "requested") {
                SectionCard(stringResource(R.string.request_sent_title)) {
                    Text(stringResource(R.string.request_sent_body, r.placeName))
                    TextButton(onClick = onBack) { Text(stringResource(R.string.ok)) }
                }
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
                    val text = ctx.getString(R.string.share_join_text, p.name, prettyCode(code), PLAY_LINK)
                    SectionCard {
                        val bmp = remember(code) { qrBitmap("messwise://join/$code") }
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
