package com.kodawick.messwise.ui.common

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kodawick.messwise.R
import com.kodawick.messwise.data.Graph
import com.kodawick.messwise.data.Images
import com.kodawick.messwise.data.model.Bill
import com.kodawick.messwise.ui.theme.LocalStatus
import java.time.LocalDate
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- status and labels

/** Colour and words for every bill state the server sends. Colour is never the only signal. */
@Composable
fun StatusChip(state: String, modifier: Modifier = Modifier) {
    val s = LocalStatus.current
    val (fg, bg) = when (state) {
        "paid", "settled", "accepted", "answered", "nothing_due", "billed" -> s.good to s.goodBg
        "pending", "to_receive", "sent_back", "void", "open_question" -> s.owe to s.oweBg
        "partly_paid" -> s.part to s.partBg
        "slip_sent", "sent", "on_leave", "open" -> s.info to s.infoBg
        else -> s.muted to s.mutedBg
    }
    Surface(color = bg, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(stateLabel(state), color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp), maxLines = 1)
    }
}

@Composable
fun stateLabel(state: String): String = when (state) {
    "paid" -> stringResource(R.string.st_paid)
    "settled" -> stringResource(R.string.st_settled)
    "pending" -> stringResource(R.string.st_pending)
    "slip_sent" -> stringResource(R.string.st_slip_sent)
    "partly_paid" -> stringResource(R.string.st_partly_paid)
    "to_receive" -> stringResource(R.string.st_to_receive)
    "nothing_due" -> stringResource(R.string.st_nothing_due)
    "sent" -> stringResource(R.string.st_slip_sent)
    "accepted" -> stringResource(R.string.st_accepted)
    "sent_back" -> stringResource(R.string.st_sent_back)
    "void" -> stringResource(R.string.st_void)
    "open" -> stringResource(R.string.st_open)
    "billed" -> stringResource(R.string.st_billed)
    "answered" -> stringResource(R.string.st_answered)
    "on_leave" -> stringResource(R.string.st_on_leave)
    "left" -> stringResource(R.string.st_left)
    else -> state.replace('_', ' ')
}

@Composable
fun typeLabel(type: String) = when (type) {
    "day" -> stringResource(R.string.type_day)
    "kitchen" -> stringResource(R.string.type_kitchen)
    else -> stringResource(R.string.type_resident)
}

@Composable
fun roleLabel(role: String, main: Boolean) = when {
    role == "owner" && main -> stringResource(R.string.role_owner_manager)
    role == "owner" -> stringResource(R.string.role_owner)
    role == "manager" && main -> stringResource(R.string.role_manager)
    role == "manager" -> stringResource(R.string.role_deputy)
    else -> stringResource(R.string.role_member)
}

@Composable
fun methodLabel(m: String) = when (m) {
    "cash" -> stringResource(R.string.pay_cash)
    "bank" -> stringResource(R.string.pay_bank)
    "easypaisa" -> "Easypaisa"
    "jazzcash" -> "JazzCash"
    else -> stringResource(R.string.pay_other)
}

@Composable
fun mealLabel(kind: String?) = when (kind) {
    "breakfast" -> stringResource(R.string.meal_breakfast)
    "lunch" -> stringResource(R.string.meal_lunch)
    "dinner" -> stringResource(R.string.meal_dinner)
    else -> stringResource(R.string.meal_other)
}

/** "Nov 2026 bill" or "Party: Grand Dinner". */
@Composable
fun billTitle(b: Bill): String =
    if (b.kind == "party") stringResource(R.string.party_bill_title, b.partyTitle ?: b.detail.title ?: "")
    else stringResource(R.string.month_bill_title, fmtMonth(b.month))

/** Owe in red, get back in green, always with the words. */
@Composable
fun BalanceText(paisa: Long, big: Boolean = false, modifier: Modifier = Modifier) {
    val s = LocalStatus.current
    val text = when {
        paisa > 0 -> stringResource(R.string.you_owe, money(paisa))
        paisa < 0 -> stringResource(R.string.you_get_back, money(-paisa))
        else -> stringResource(R.string.nothing_due)
    }
    Text(text, modifier = modifier, color = if (paisa > 0) s.owe else if (paisa < 0) s.good else MaterialTheme.colorScheme.onSurfaceVariant,
        style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

// ---------------------------------------------------------------- inputs

/** One choice out of a few, as chips. */
@Composable
fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) ->
            FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label) })
        }
    }
}

/** A cups or plates counter: − 3 +. */
@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, enabled: Boolean = true, max: Int = 300) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { if (value > 0) onChange(value - 1) }, enabled = enabled && value > 0, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Remove, stringResource(R.string.less))
        }
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 44.dp))
        FilledTonalIconButton(onClick = { if (value < max) onChange(value + 1) }, enabled = enabled && value < max, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Add, stringResource(R.string.more_one))
        }
    }
}

/** ‹ Nov 2026 › — never past the current month. */
@Composable
fun MonthSwitcher(month: String, onChange: (String) -> Unit) {
    val m = try { LocalDate.parse(month) } catch (_: Exception) { todayPk().withDayOfMonth(1) }
    val current = todayPk().withDayOfMonth(1)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(m.minusMonths(1).toString()) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_month)) }
        Text(fmtMonth(m.toString()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange(m.plusMonths(1).toString()) }, enabled = m.isBefore(current)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_month))
        }
    }
}

fun thisMonth(): String = todayPk().withDayOfMonth(1).toString()

// ---------------------------------------------------------------- photos

/**
 * Pick a photo from the gallery or take one with the camera, compress it and upload it.
 * [onUploaded] gets the storage path. [folder]: "receipts", "slips", "logo", or null (private book).
 */
@Composable
fun PhotoUpload(placeId: String?, folder: String?, path: String?, onUploaded: (String?) -> Unit, label: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    fun upload(uri: Uri) {
        scope.launch {
            busy = true; error = null
            try {
                val bytes = Images.compress(ctx, uri)
                onUploaded(Graph.api.upload(placeId, folder, bytes))
            } catch (e: Exception) {
                error = plainError(ctx, e)
            } finally { busy = false }
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) upload(uri) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let { upload(Uri.parse(it)) } }
    SectionCard(label) {
        if (path != null) StoredImage(path, Modifier.fillMaxWidth().heightIn(max = 260.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(stringResource(R.string.gallery), icon = Icons.Filled.Image, modifier = Modifier.weight(1f), enabled = !busy, onClick = {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
            SecondaryButton(stringResource(R.string.camera), icon = Icons.Filled.PhotoCamera, modifier = Modifier.weight(1f), enabled = !busy, onClick = {
                val u = Images.newCameraUri(ctx)
                cameraUri = u.toString()
                camera.launch(u)
            })
        }
        if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.uploading))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (path != null && !busy) Text(stringResource(R.string.photo_added), style = MaterialTheme.typography.bodySmall, color = LocalStatus.current.good)
    }
}

/** Shows a stored photo through a short-lived signed link. */
@Composable
fun StoredImage(path: String, modifier: Modifier = Modifier) {
    var url by remember(path) { mutableStateOf<String?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        try { url = Graph.api.signedUrl(path) } catch (_: Exception) { failed = true }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
        when {
            url != null -> AsyncImage(model = url, contentDescription = stringResource(R.string.photo), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
            failed -> Text(stringResource(R.string.photo_not_available), style = MaterialTheme.typography.bodySmall)
            else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

/** A tappable row with a label and an optional value, used in lists of settings and links. */
@Composable
fun LinkRow(title: String, subtitle: String? = null, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun SmallLabel(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, modifier = modifier)
}

@Composable
fun Gap(h: Int = 4) = Spacer(Modifier.height(h.dp))
