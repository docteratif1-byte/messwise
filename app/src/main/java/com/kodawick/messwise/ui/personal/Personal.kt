package com.kodawick.messwise.ui.personal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kodawick.messwise.R
import com.kodawick.messwise.core.Money
import com.kodawick.messwise.data.Graph
import com.kodawick.messwise.ui.Nav
import com.kodawick.messwise.ui.Routes
import com.kodawick.messwise.ui.common.AmountField
import com.kodawick.messwise.ui.common.AppScreen
import com.kodawick.messwise.ui.common.ChoiceRow
import com.kodawick.messwise.ui.common.ConfirmDialog
import com.kodawick.messwise.ui.common.DateField
import com.kodawick.messwise.ui.common.EmptyState
import com.kodawick.messwise.ui.common.Hint
import com.kodawick.messwise.ui.common.InfoRow
import com.kodawick.messwise.ui.common.Loaded
import com.kodawick.messwise.ui.common.MonthSwitcher
import com.kodawick.messwise.ui.common.PhotoUpload
import com.kodawick.messwise.ui.common.PrimaryButton
import com.kodawick.messwise.ui.common.ScrollColumn
import com.kodawick.messwise.ui.common.SecondaryButton
import com.kodawick.messwise.ui.common.SectionCard
import com.kodawick.messwise.ui.common.SmallLabel
import com.kodawick.messwise.ui.common.TextInput
import com.kodawick.messwise.ui.common.fmtDate
import com.kodawick.messwise.ui.common.money
import com.kodawick.messwise.ui.common.rememberAction
import com.kodawick.messwise.ui.common.rememberLoad
import com.kodawick.messwise.ui.common.thisMonth
import com.kodawick.messwise.ui.common.todayPk
import com.kodawick.messwise.ui.theme.LocalStatus

val CATEGORIES = listOf("food", "transport", "shopping", "bills", "health", "education", "family", "other")

@Composable
fun categoryLabel(c: String) = when (c) {
    "food" -> stringResource(R.string.cat_food)
    "transport" -> stringResource(R.string.cat_transport)
    "shopping" -> stringResource(R.string.cat_shopping)
    "bills" -> stringResource(R.string.cat_bills)
    "health" -> stringResource(R.string.cat_health)
    "education" -> stringResource(R.string.cat_education)
    "family" -> stringResource(R.string.cat_family)
    "other" -> stringResource(R.string.cat_other)
    else -> c
}

/** The private book: only this person sees it, not managers or the owner. Mess charges are shown alongside. */
@Composable
fun PersonalTab(nav: Nav, pad: PaddingValues) {
    val ctx = LocalContext.current
    var month by rememberSaveable { mutableStateOf(thisMonth()) }
    var budget by remember { mutableStateOf(false) }
    val h = rememberLoad("personal", month, cached = { Graph.repo.personalCached(month) }) { Graph.repo.personal(month) }
    val action = rememberAction()
    val s = LocalStatus.current
    Box(Modifier.fillMaxSize().padding(pad)) {
        Loaded(h) { p ->
            ScrollColumn(PaddingValues(0.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    SmallLabel("  " + stringResource(R.string.book_private))
                }
                MonthSwitcher(month) { month = it }
                val mess = p.mess.sumOf { it.chargesPaisa }
                SectionCard {
                    InfoRow(stringResource(R.string.my_own_spending), money(p.personalTotalPaisa), strong = true)
                    p.mess.forEach { InfoRow(stringResource(R.string.mess_charges_at, it.placeName), money(it.chargesPaisa)) }
                    if (p.mess.isNotEmpty()) InfoRow(stringResource(R.string.month_all_in), money(p.personalTotalPaisa + mess), strong = true)
                    val b = p.budgetPaisa
                    if (b != null && b > 0) {
                        val used = p.personalTotalPaisa + mess
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(progress = { (used.toFloat() / b).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
                            color = if (used > b) s.owe else MaterialTheme.colorScheme.primary)
                        SmallLabel(if (used > b) stringResource(R.string.over_budget, money(used - b), money(b)) else stringResource(R.string.budget_left, money(b - used), money(b)),
                            if (used > b) s.owe else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { budget = true }) { Text(stringResource(if (b == null) R.string.set_budget else R.string.change_budget)) }
                }
                if (p.byCategory.isNotEmpty()) SectionCard(stringResource(R.string.by_category)) {
                    p.byCategory.forEach { InfoRow(categoryLabel(it.category), money(it.amountPaisa)) }
                }
                if (p.items.isEmpty()) EmptyState(Icons.AutoMirrored.Filled.MenuBook, stringResource(R.string.book_empty), stringResource(R.string.book_empty_body))
                else SectionCard(stringResource(R.string.entries)) {
                    p.items.forEachIndexed { i, it ->
                        if (i > 0) HorizontalDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(it.note.ifBlank { categoryLabel(it.category) }, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                SmallLabel(categoryLabel(it.category) + " · " + fmtDate(it.day))
                            }
                            Text(money(it.amountPaisa), fontWeight = FontWeight.Bold)
                            TextButton(onClick = { nav.navigate(Routes.personal(it.id, month)) }) { Text(stringResource(R.string.edit)) }
                        }
                    }
                }
                Spacer(Modifier.height(72.dp))
            }
            if (budget) BudgetDialog(p.budgetPaisa, onDismiss = { budget = false }) { v ->
                action.run(success = ctx.getString(R.string.saved)) { h.data = Graph.repo.budgetSet(v) }
            }
        }
        ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.personal()) }, icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(stringResource(R.string.add_expense)) }, containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("add_personal"))
    }
}

@Composable
private fun BudgetDialog(current: Long?, onDismiss: () -> Unit, onSave: (Long?) -> Unit) {
    var v by rememberSaveable { mutableStateOf(current?.let { Money.toInput(it) } ?: "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.monthly_budget)) },
        text = { Column { AmountField(v, { v = it }, stringResource(R.string.amount)); Hint(stringResource(R.string.budget_hint)) } },
        confirmButton = { TextButton(onClick = { onDismiss(); onSave(Money.parse(v)?.takeIf { it > 0 }) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun PersonalFormScreen(id: String?, month0: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val month = remember { month0 ?: thisMonth() }
    val existing = remember(id) { id?.let { i -> Graph.repo.personalCached(month)?.items?.firstOrNull { it.id == i } } }
    var amount by rememberSaveable { mutableStateOf(existing?.let { Money.toInput(it.amountPaisa) } ?: "") }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: "food") }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }
    var day by rememberSaveable { mutableStateOf(existing?.day ?: todayPk().toString()) }
    var receipt by rememberSaveable { mutableStateOf(existing?.receiptPath) }
    var askDelete by remember { mutableStateOf(false) }
    val action = rememberAction()
    val paisa = Money.parse(amount)
    AppScreen(stringResource(if (id == null) R.string.add_expense else R.string.edit_expense), onBack = onBack) { pad ->
        ScrollColumn(pad) {
            Hint(stringResource(R.string.book_private))
            AmountField(amount, { amount = it }, stringResource(R.string.amount), modifier = Modifier.testTag("personal_amount"))
            ChoiceRow(CATEGORIES.map { it to categoryLabel(it) }, category, { category = it })
            TextInput(note, { note = it.take(200) }, stringResource(R.string.what_for))
            DateField(day, { day = it }, stringResource(R.string.day))
            PhotoUpload(null, null, receipt, { receipt = it }, stringResource(R.string.receipt_optional))
            PrimaryButton(stringResource(R.string.save), enabled = paisa != null && paisa > 0, busy = action.busy, modifier = Modifier.testTag("personal_save"), onClick = {
                action.run(success = ctx.getString(R.string.saved), after = { onBack() }) {
                    Graph.repo.personalSave(id, paisa!!, category, note.trim(), day, receipt)
                }
            })
            if (id != null) SecondaryButton(stringResource(R.string.delete), icon = Icons.Filled.Delete, onClick = { askDelete = true })
        }
    }
    if (askDelete && id != null) ConfirmDialog(stringResource(R.string.delete_expense), stringResource(R.string.delete_expense_body), stringResource(R.string.delete),
        danger = true, onConfirm = { action.run(after = { onBack() }) { Graph.repo.personalDelete(id) } }, onDismiss = { askDelete = false })
}
