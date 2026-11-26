package com.kodawick.messwise.ui.entries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiFoodBeverage
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.kodawick.messwise.R
import com.kodawick.messwise.data.Graph
import com.kodawick.messwise.data.model.Entry
import com.kodawick.messwise.ui.Nav
import com.kodawick.messwise.ui.Routes
import com.kodawick.messwise.ui.bills.BillRow
import com.kodawick.messwise.ui.common.AppScreen
import com.kodawick.messwise.ui.common.ChoiceRow
import com.kodawick.messwise.ui.common.EmptyState
import com.kodawick.messwise.ui.common.Hint
import com.kodawick.messwise.ui.common.InfoRow
import com.kodawick.messwise.ui.common.Loaded
import com.kodawick.messwise.ui.common.MonthSwitcher
import com.kodawick.messwise.ui.common.ReasonDialog
import com.kodawick.messwise.ui.common.ScrollColumn
import com.kodawick.messwise.ui.common.SecondaryButton
import com.kodawick.messwise.ui.common.SectionCard
import com.kodawick.messwise.ui.common.SmallLabel
import com.kodawick.messwise.ui.common.StatusChip
import com.kodawick.messwise.ui.common.StoredImage
import com.kodawick.messwise.ui.common.fmtDate
import com.kodawick.messwise.ui.common.fmtDateTime
import com.kodawick.messwise.ui.common.mealLabel
import com.kodawick.messwise.ui.common.money
import com.kodawick.messwise.ui.common.rememberAction
import com.kodawick.messwise.ui.common.rememberLoad
import com.kodawick.messwise.ui.common.thisMonth

fun entryIcon(e: Entry): ImageVector = when (e.type) {
    "meal" -> Icons.Filled.Restaurant
    "party" -> Icons.Filled.Celebration
    else -> if (e.bill == "tea") Icons.Filled.EmojiFoodBeverage else Icons.Filled.ShoppingBasket
}

@Composable
fun entryTitle(e: Entry): String = when (e.type) {
    "meal" -> mealLabel(e.kind) + (e.title?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
    "party" -> e.title ?: stringResource(R.string.party)
    else -> e.item ?: ""
}

@Composable
fun entryKindLabel(e: Entry): String = when (e.type) {
    "meal" -> stringResource(R.string.meal_bill)
    "party" -> stringResource(R.string.party)
    else -> stringResource(if (e.bill == "tea") R.string.tea_bill else R.string.kitchen_bill)
}

/** Every purchase, meal and party of a month. Everyone can see them; managers add and edit. */
@Composable
fun EntriesTab(placeId: String, canManage: Boolean, nav: Nav, pad: PaddingValues) {
    var month by rememberSaveable { mutableStateOf(thisMonth()) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var menu by remember { mutableStateOf(false) }
    val h = rememberLoad("entries", placeId, month, cached = { Graph.repo.entriesCached(placeId, month) }) { Graph.repo.entries(placeId, month) }
    Box(Modifier.fillMaxSize().padding(pad)) {
        Loaded(h) { list ->
            val items = list.items.filter {
                when (filter) {
                    "tea" -> it.type == "purchase" && it.bill == "tea"
                    "kitchen" -> it.type == "purchase" && it.bill == "kitchen"
                    "meal" -> it.type == "meal"
                    "party" -> it.type == "party"
                    else -> true
                }
            }
            ScrollColumn(PaddingValues(0.dp)) {
                MonthSwitcher(month) { month = it }
                ChoiceRow(listOf("all" to stringResource(R.string.all), "tea" to stringResource(R.string.tea), "kitchen" to stringResource(R.string.kitchen),
                    "meal" to stringResource(R.string.meals), "party" to stringResource(R.string.parties)), filter, { filter = it })
                val active = items.filter { !it.isVoid }
                if (items.isNotEmpty()) SmallLabel(stringResource(R.string.entries_total, active.size, money(active.sumOf { it.amountPaisa })))
                if (items.isEmpty()) EmptyState(Icons.AutoMirrored.Filled.ReceiptLong, stringResource(R.string.no_entries),
                    stringResource(if (canManage) R.string.no_entries_manager else R.string.no_entries_member))
                items.forEach { e -> EntryRow(e) { nav.navigate(Routes.entry(e.type, e.id)) } }
                Spacer(Modifier.size(72.dp))
            }
        }
        if (canManage) Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            ExtendedFloatingActionButton(onClick = { menu = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.add)) },
                containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary, modifier = Modifier.testTag("add_entry"))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.tea_purchase)) }, leadingIcon = { Icon(Icons.Filled.EmojiFoodBeverage, null) },
                    onClick = { menu = false; nav.navigate(Routes.purchase(placeId, bill = "tea")) })
                DropdownMenuItem(text = { Text(stringResource(R.string.kitchen_purchase)) }, leadingIcon = { Icon(Icons.Filled.ShoppingBasket, null) },
                    onClick = { menu = false; nav.navigate(Routes.purchase(placeId, bill = "kitchen")) })
                DropdownMenuItem(text = { Text(stringResource(R.string.add_meal)) }, leadingIcon = { Icon(Icons.Filled.Restaurant, null) },
                    onClick = { menu = false; nav.navigate(Routes.meal(placeId)) })
                DropdownMenuItem(text = { Text(stringResource(R.string.add_party)) }, leadingIcon = { Icon(Icons.Filled.Celebration, null) },
                    onClick = { menu = false; nav.navigate(Routes.party(placeId)) })
                DropdownMenuItem(text = { Text(stringResource(R.string.count_tea)) }, leadingIcon = { Icon(Icons.Filled.EmojiFoodBeverage, null) },
                    onClick = { menu = false; nav.navigate(Routes.tea(placeId)) })
            }
        }
    }
}

@Composable
fun EntryRow(e: Entry, onClick: () -> Unit) {
    SectionCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(entryIcon(e), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entryTitle(e), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    textDecoration = if (e.isVoid) TextDecoration.LineThrough else null)
                SmallLabel(entryKindLabel(e) + " · " + fmtDate(e.day) + (e.payers.firstOrNull()?.let { " · " + stringResource(R.string.paid_by_x, it.name) } ?: ""))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(money(e.amountPaisa), fontWeight = FontWeight.Bold, textDecoration = if (e.isVoid) TextDecoration.LineThrough else null)
                if (e.isVoid) StatusChip("void") else if (e.version > 1) SmallLabel(stringResource(R.string.edited))
            }
        }
    }
}

/** Full detail of an entry with who paid, each share, the change history and questions. */
@Composable
fun EntryScreen(type: String, id: String, nav: Nav, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val h = rememberLoad("entry", type, id) { Graph.repo.entry(type, id) }
    var askVoid by remember { mutableStateOf(false) }
    val action = rememberAction()
    AppScreen(stringResource(R.string.entry), onBack = onBack) { pad ->
        Loaded(h) { e ->
            ScrollColumn(pad) {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(entryIcon(e), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(entryTitle(e), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (e.isVoid) StatusChip("void")
                    }
                    InfoRow(stringResource(R.string.kind), entryKindLabel(e))
                    InfoRow(stringResource(R.string.amount), money(e.amountPaisa), strong = true)
                    InfoRow(stringResource(R.string.day), fmtDate(e.day))
                    if (e.type == "party") {
                        InfoRow(stringResource(R.string.persons_attended), e.heads.toString())
                        e.perHeadPaisa?.let { InfoRow(stringResource(R.string.share_per_head), money(it)) }
                        InfoRow(stringResource(R.string.billed_as), stringResource(if (e.addToMonth) R.string.in_month_bill else R.string.separate_party_bill))
                    }
                    if (e.note.isNotBlank()) InfoRow(stringResource(R.string.note), e.note)
                    e.createdByName?.let { InfoRow(stringResource(R.string.added_by), it + " · " + fmtDateTime(e.createdAt)) }
                    if (e.isVoid) e.voidReason?.let { InfoRow(stringResource(R.string.void_reason), it) }
                }
                SectionCard(stringResource(R.string.who_paid)) {
                    e.payers.forEach { InfoRow(it.name, money(it.amountPaisa)) }
                    Hint(stringResource(R.string.payer_credit_hint))
                }
                if (e.shares.isNotEmpty()) SectionCard(stringResource(R.string.shares)) {
                    e.shares.forEach { InfoRow(it.name, money(it.sharePaisa)) }
                    val pg = e.people.filter { it.subject == "personal_guest" }
                    if (pg.isNotEmpty()) Hint(stringResource(R.string.personal_guests_line, pg.joinToString(", ") { (it.name ?: "") + " +" + (if (e.type == "party") it.heads else it.units.toInt()) }))
                    val og = e.people.filter { it.subject == "office_guest" }.sumOf { if (e.type == "party") it.heads else it.units.toInt() }
                    if (og > 0) Hint(stringResource(R.string.office_guests_line, og))
                }
                if (e.type == "party" && e.bills.isNotEmpty()) SectionCard(stringResource(R.string.paid_pending)) {
                    e.bills.forEach { b -> BillRow(b, showName = true, onClick = { nav.navigate(Routes.bill(b.id)) }) }
                }
                e.receiptPath?.let { SectionCard(stringResource(R.string.receipt_photo)) { StoredImage(it) } }
                if (e.versions.isNotEmpty()) SectionCard(stringResource(R.string.change_history)) {
                    e.versions.forEachIndexed { i, v ->
                        if (i > 0) HorizontalDivider()
                        Text(stringResource(R.string.version_line, v.version, v.changedByName ?: "", fmtDateTime(v.changedAt)), style = MaterialTheme.typography.labelMedium)
                        Text(v.reason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (e.questions.isNotEmpty()) SectionCard(stringResource(R.string.questions)) {
                    e.questions.forEach { q ->
                        Row { Text(q.askedBy ?: "", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); StatusChip(if (q.status == "open") "open_question" else "answered") }
                        Text(q.text)
                        q.answer?.let { Text("↳ $it", color = MaterialTheme.colorScheme.primary) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (e.canEdit && !e.isVoid) {
                        SecondaryButton(stringResource(R.string.edit), icon = Icons.Filled.Edit, modifier = Modifier.weight(1f), onClick = {
                            nav.navigate(when (e.type) {
                                "meal" -> Routes.meal(e.placeId, e.id)
                                "party" -> Routes.party(e.placeId, e.id)
                                else -> Routes.purchase(e.placeId, e.id, e.bill ?: "tea")
                            })
                        })
                        SecondaryButton(stringResource(R.string.void_entry), icon = Icons.Filled.Block, modifier = Modifier.weight(1f), onClick = { askVoid = true })
                    }
                }
                if (!e.canEdit && !e.isVoid) Hint(stringResource(R.string.entry_locked_or_member))
                SecondaryButton(stringResource(R.string.ask_about_entry), icon = Icons.Filled.QuestionAnswer,
                    onClick = { nav.navigate(Routes.ask(e.placeId, e.type, e.id)) })
            }
            if (askVoid) ReasonDialog(stringResource(R.string.void_title), stringResource(R.string.void_body), stringResource(R.string.void_entry),
                minLength = 3, onConfirm = { r -> action.run(success = ctx.getString(R.string.entry_voided)) { h.data = Graph.repo.voidEntry(e.type, e.id, r) } },
                onDismiss = { askVoid = false })
        }
    }
}
