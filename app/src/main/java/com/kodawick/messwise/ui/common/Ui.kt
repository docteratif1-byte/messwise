package com.kodawick.messwise.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodawick.messwise.R
import com.kodawick.messwise.core.Money
import com.kodawick.messwise.data.ApiException
import com.kodawick.messwise.data.Graph
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- loading

/** Holds what a screen shows: last good data (also from the offline cache), the latest error, and whether it is loading. */
class LoadHandle<T>(private val scope: CoroutineScope, private val fetch: suspend () -> T, initial: T?) {
    var data by mutableStateOf(initial)
    var error by mutableStateOf<Throwable?>(null)
    var loading by mutableStateOf(false)
    var offline by mutableStateOf(false)

    fun refresh() {
        scope.launch {
            loading = true
            try {
                data = fetch()
                error = null
                offline = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e is ApiException && e.isNetwork && data != null) offline = true else error = e
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun <T> rememberLoad(vararg keys: Any?, cached: () -> T? = { null }, fetch: suspend () -> T): LoadHandle<T> {
    val scope = rememberCoroutineScope()
    val changes by Graph.changes.collectAsState()
    val handle = remember(*keys) { LoadHandle(scope, fetch, cached()) }
    LaunchedEffect(handle, changes) { handle.refresh() }
    return handle
}

/** Shows a spinner, an error with Retry, or the content; pull down to refresh. */
@Composable
fun <T> Loaded(h: LoadHandle<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val d = h.data
    PullToRefreshBox(isRefreshing = h.loading && d != null, onRefresh = { h.refresh() }, modifier = modifier.fillMaxSize()) {
        when {
            d != null -> Column(Modifier.fillMaxSize()) {
                if (h.offline) OfflineBanner()
                Box(Modifier.weight(1f)) { content(d) }
            }
            h.error != null -> ErrorState(h.error!!, onRetry = { h.refresh() })
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable
fun OfflineBanner() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CloudOff, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.offline_saved), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ErrorState(e: Throwable, onRetry: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Spacer(Modifier.height(80.dp))
        Icon(if (e is ApiException && e.isNetwork) Icons.Filled.CloudOff else Icons.Filled.ErrorOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(errorText(e), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
fun errorText(e: Throwable): String {
    if (e !is ApiException) return stringResource(R.string.err_unknown)
    val head = when (e.code) {
        "NETWORK" -> return stringResource(R.string.err_network)
        "NOT_AUTH" -> stringResource(R.string.err_not_auth)
        "FORBIDDEN" -> stringResource(R.string.err_forbidden)
        "NOT_FOUND" -> stringResource(R.string.err_not_found)
        "LIMIT" -> stringResource(R.string.err_limit)
        "DUPLICATE" -> stringResource(R.string.err_duplicate)
        "IMMUTABLE" -> stringResource(R.string.err_immutable)
        "STATE", "INVALID" -> stringResource(R.string.err_check)
        else -> stringResource(R.string.err_server)
    }
    val detail = e.message?.takeIf { it.isNotBlank() && e.code != "SERVER" }?.replaceFirstChar { it.uppercase() }
    return if (detail != null) "$head\n$detail" else head
}

// ---------------------------------------------------------------- actions with feedback

class Feedback(val snackbar: SnackbarHostState, val scope: CoroutineScope)
val LocalFeedback = compositionLocalOf<Feedback?> { null }

/** Runs a change on the server with a busy state; shows the error or the success message, then refreshes open screens. */
class Action(private val scope: CoroutineScope, private val snack: SnackbarHostState?, private val errorToText: (Throwable) -> String) {
    var busy by mutableStateOf(false)
    var lastError by mutableStateOf<Throwable?>(null)

    fun run(success: String? = null, after: (suspend () -> Unit)? = null, block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            lastError = null
            try {
                block()
                Graph.changed()
                if (success != null) snack?.showSnackbar(success)
                after?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                snack?.showSnackbar(errorToText(e).replace('\n', ' '))
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun rememberAction(): Action {
    val scope = rememberCoroutineScope()
    val fb = LocalFeedback.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember { Action(scope, fb?.snackbar) { e -> plainError(ctx, e) } }
}

fun plainError(ctx: android.content.Context, e: Throwable): String {
    if (e !is ApiException) return ctx.getString(R.string.err_unknown)
    if (e.code == "NETWORK") return ctx.getString(R.string.err_network)
    val head = when (e.code) {
        "NOT_AUTH" -> R.string.err_not_auth; "FORBIDDEN" -> R.string.err_forbidden; "NOT_FOUND" -> R.string.err_not_found
        "LIMIT" -> R.string.err_limit; "DUPLICATE" -> R.string.err_duplicate; "IMMUTABLE" -> R.string.err_immutable
        "STATE", "INVALID" -> R.string.err_check; else -> R.string.err_server
    }
    val detail = e.message?.takeIf { it.isNotBlank() && e.code != "SERVER" }?.replaceFirstChar { it.uppercase() }
    return ctx.getString(head) + (detail?.let { ": $it" } ?: "")
}

// ---------------------------------------------------------------- layout

@Composable
fun AppScreen(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    floating: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val snack = LocalFeedback.current?.snackbar ?: remember { SnackbarHostState() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = floating,
        bottomBar = bottomBar,
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

@Composable
fun ScrollColumn(padding: PaddingValues, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null,
                content: @Composable ColumnScope.() -> Unit) {
    val m = if (onClick != null) modifier.fillMaxWidth().clickable(onClick = onClick) else modifier.fillMaxWidth()
    Card(
        modifier = m,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, strong: Boolean = false, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium, color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 220.dp))
    }
}

@Composable
fun BigNumber(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

@Composable
fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f), shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, icon: ImageVector? = null) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondary)
        else {
            if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(48.dp), shape = MaterialTheme.shapes.medium) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text)
    }
}

// ---------------------------------------------------------------- formatting

fun money(paisa: Long) = Money.format(paisa)

@Composable
fun locale(): Locale = LocalConfiguration.current.locales[0]

private val pk = ZoneId.of("Asia/Karachi")

@Composable
fun fmtMonth(iso: String?): String = remember(iso, locale()) {
    try { LocalDate.parse(iso!!.take(10)).format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())) } catch (_: Exception) { iso ?: "" }
}

@Composable
fun fmtDate(iso: String?): String = remember(iso, locale()) { fmtDatePlain(iso) }

fun fmtDatePlain(iso: String?): String = try {
    if (iso == null) "" else if (iso.length <= 10) LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
    else OffsetDateTime.parse(iso).atZoneSameInstant(pk).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
} catch (_: Exception) { iso ?: "" }

fun fmtDateTimePlain(iso: String?): String = try {
    if (iso == null) "" else OffsetDateTime.parse(iso).atZoneSameInstant(pk).format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.getDefault()))
} catch (_: Exception) { fmtDatePlain(iso) }

@Composable
fun fmtDateTime(iso: String?): String = remember(iso, locale()) { fmtDateTimePlain(iso) }

fun todayPk(): LocalDate = LocalDate.now(pk)

fun monthPlain(iso: String?): String = try { LocalDate.parse(iso!!.take(10)).format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())) } catch (_: Exception) { iso ?: "" }

// ---------------------------------------------------------------- inputs and dialogs

@Composable
fun AmountField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, supporting: String? = null, isError: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(Money.toAsciiDigits(v).filter { it.isDigit() || it == '.' || it == ',' }) },
        label = { Text(label) },
        prefix = { Text("Rs ") },
        singleLine = true,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun TextInput(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, singleLine: Boolean = true, minLines: Int = 1,
              keyboard: KeyboardType = KeyboardType.Text, supporting: String? = null, isError: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine, minLines = minLines,
        isError = isError, supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = modifier.fillMaxWidth())
}

/** A date chosen from a calendar. [value] and the result are ISO dates (2027-01-15). */
@Composable
fun DateField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(value = fmtDate(value), onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.CalendarMonth, null) }, modifier = Modifier.fillMaxWidth())
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        val initial = try { LocalDate.parse(value).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli() } catch (_: Exception) { null }
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate().toString()) }
                    open = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Asks for a reason before a sensitive change (edit, void, send back). The reason is kept in the history. */
@Composable
fun ReasonDialog(title: String, body: String?, confirm: String, minLength: Int = 5, onConfirm: (String) -> Unit, onDismiss: () -> Unit, danger: Boolean = true) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(stringResource(R.string.reason)) }, minLines = 2,
                    supportingText = { Text(stringResource(R.string.reason_kept)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = text.trim().length >= minLength, onClick = { onDismiss(); onConfirm(text.trim()) }) {
                Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun AnimatedHint(visible: Boolean, text: String) {
    AnimatedVisibility(visible) { Hint(text) }
}

@Composable
fun Avatar(name: String, size: Int = 40, color: Color = MaterialTheme.colorScheme.primaryContainer) {
    val initials = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
    Box(Modifier.size(size.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(initials, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
