package com.kodawick.messwise.ui.bills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kodawick.messwise.R
import com.kodawick.messwise.core.Money
import com.kodawick.messwise.data.Graph
import com.kodawick.messwise.data.model.Bill
import com.kodawick.messwise.data.model.Payment
import com.kodawick.messwise.data.model.WaitingPayment
import com.kodawick.messwise.ui.Nav
import com.kodawick.messwise.ui.Routes
import com.kodawick.messwise.ui.common.AmountField
import com.kodawick.messwise.ui.common.AppScreen
import com.kodawick.messwise.ui.common.BalanceText
import com.kodawick.messwise.ui.common.BigNumber
import com.kodawick.messwise.ui.common.ChoiceRow
import com.kodawick.messwise.ui.common.ConfirmDialog
import com.kodawick.messwise.ui.common.EmptyState
import com.kodawick.messwise.ui.common.Hint
import com.kodawick.messwise.ui.common.InfoRow
import com.kodawick.messwise.ui.common.Loaded
import com.kodawick.messwise.ui.common.PhotoUpload
import com.kodawick.messwise.ui.common.PrimaryButton
import com.kodawick.messwise.ui.common.ReasonDialog
import com.kodawick.messwise.ui.common.ScrollColumn
import com.kodawick.messwise.ui.common.SecondaryButton
import com.kodawick.messwise.ui.common.SectionCard
import com.kodawick.messwise.ui.common.SmallLabel
import com.kodawick.messwise.ui.common.StatusChip
import com.kodawick.messwise.ui.common.StoredImage
import com.kodawick.messwise.ui.common.TextInput
import com.kodawick.messwise.ui.common.billTitle
import com.kodawick.messwise.ui.common.fmtDate
import com.kodawick.messwise.ui.common.fmtDateTime
import com.kodawick.messwise.ui.common.fmtMonth
import com.kodawick.messwise.ui.common.mealLabel
import com.kodawick.messwise.ui.common.methodLabel
import com.kodawick.messwise.ui.common.money
import com.kodawick.messwise.ui.common.rememberAction
import com.kodawick.messwise.ui.common.rememberLoad
import com.kodawick.messwise.ui.home.warningText
import com.kodawick.messwise.ui.theme.LocalStatus

private fun rate(r: Double?) = r?.let { Math.round(it * 100) }

// ---------------------------------------------------------------- month sheet

/** The month: Tea Bill and Kitchen Bill worked out for everyone, then locked as final bills by the manager. */
@Composable
fun MonthScreen(placeId: String, month: String, nav: Nav, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val h = rememberLoad("month", placeId, month, cached = { Graph.repo.monthCached(placeId, month) }) { Graph.repo.month(placeId, month) }
    var askBill by remember { mutableStateOf(false) }
    var askReopen by remember { mutableStateOf(false) }
    val action = rememberAction()
    AppScreen(stringResource(R.string.month_sheet_title, fmtMonth(month)), onBack = onBack) { pad ->
        Loaded(h) { m ->
            ScrollColumn(pad) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (m.status == "billed") R.string.month_billed else R.string.month_open), modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    StatusChip(m.status)
                }
                if (m.status != "billed") Hint(stringResource(R.string.month_open_hint, fmtDate(m.upto)))
                m.reopenReason?.let { Hint(stringResource(R.string.reopened_because, it)) }
                m.warnings.forEach { Text("• " + warningText(it), color = LocalStatus.current.part, style = MaterialTheme.typography.bodySmall) }

                if (m.sees.tea) SectionCard(stringResource(R.string.tea_bill)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BigNumber(stringResource(R.string.tea_spent), money(m.tea.totalPaisa), Modifier.weight(1f))
                        BigNumber(stringResource(R.string.cups_made), m.tea.cupsTotal.toString(), Modifier.weight(1f))
                    }
                    InfoRow(stringResource(R.string.tea_rate_per_cup), rate(m.tea.rate)?.let { money(it) } ?: "—", strong = true)
                    Hint(stringResource(R.string.tea_rate_formula, money(m.tea.totalPaisa), m.tea.cupsTotal))
                    if (m.tea.cupsPersonal > 0) InfoRow(stringResource(R.string.personal_guest_cups), m.tea.cupsPersonal.toString())
                    if (m.tea.cupsOffice > 0) {
                        InfoRow(stringResource(R.string.office_guest_cups), m.tea.cupsOffice.toString())
                        m.tea.officeSharePaisa?.let { InfoRow(stringResource(R.string.office_cups_each, m.tea.teaMembers), money(it)) }
                    }
                }
                if (m.sees.kitchen) SectionCard(stringResource(R.string.kitchen_bill)) {
                    InfoRow(stringResource(R.string.kitchen_shared), money(m.kitchen.sharedPaisa))
                    InfoRow(stringResource(R.string.member_days), m.kitchen.memberDays.toString())
                    InfoRow(stringResource(R.string.per_day), rate(m.kitchen.perDay)?.let { money(it) } ?: "—")
                    if (m.sees.meals) InfoRow(stringResource(R.string.meals_spent), money(m.kitchen.mealsPaisa))
                    if (m.sees.meals && m.partiesPaisa > 0) InfoRow(stringResource(R.string.parties_in_month), money(m.partiesPaisa))
                    Hint(stringResource(R.string.kitchen_formula))
                }
                SectionCard(stringResource(R.string.everyone)) {
                    if (!m.sees.everyone) Hint(stringResource(R.string.others_lines_hint))
                    m.rows.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider()
                        val bill = m.bills.firstOrNull { it.memberId == r.memberId && it.kind == "month" }
                        Column(Modifier.fillMaxWidth().let { mod -> if (bill != null) mod.clickable { nav.navigate(Routes.bill(bill.id)) } else mod }.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(money(r.totalPaisa), fontWeight = FontWeight.Bold)
                            }
                            SmallLabel(listOfNotNull(
                                if (r.cups + r.guestCups > 0) stringResource(R.string.row_tea, r.cups + r.guestCups, money(r.teaPaisa)) else null,
                                r.kitchenDays?.let { stringResource(R.string.row_kitchen, it, money(r.kitchenSharedPaisa)) },
                                if (r.mealsPaisa > 0) stringResource(R.string.row_meals, r.mealsCount, money(r.mealsPaisa)) else null,
                                if (r.partyPaisa > 0) stringResource(R.string.row_party, money(r.partyPaisa)) else null,
                                if (r.creditPaisa > 0) stringResource(R.string.row_credit, money(r.creditPaisa)) else null,
                            ).joinToString(" · "))
                            bill?.let { StatusChip(it.status) }
                        }
                    }
                    if (m.rows.isEmpty()) Hint(stringResource(R.string.no_one_this_month))
                }
                if (m.canBill) {
                    PrimaryButton(stringResource(R.string.make_final_bill), icon = Icons.Filled.Lock, busy = action.busy, onClick = { askBill = true },
                        modifier = Modifier.testTag("make_bill"))
                    Hint(stringResource(R.string.make_final_bill_hint))
                }
                if (m.canReopen) SecondaryButton(stringResource(R.string.reopen_month), icon = Icons.Filled.LockOpen, onClick = { askReopen = true })
                SecondaryButton(stringResource(R.string.reports), onClick = { nav.navigate(Routes.reports(placeId)) })
            }
            if (askBill) ConfirmDialog(stringResource(R.string.make_final_bill), stringResource(R.string.make_final_bill_confirm, fmtMonth(month)),
                stringResource(R.string.make_bills), onConfirm = { action.run(success = ctx.getString(R.string.bills_made)) { h.data = Graph.repo.monthBill(placeId, month) } },
                onDismiss = { askBill = false })
            if (askReopen) ReasonDialog(stringResource(R.string.reopen_month), stringResource(R.string.reopen_body), stringResource(R.string.reopen_month),
                onConfirm = { r -> action.run { h.data = Graph.repo.monthReopen(placeId, month, r) } }, onDismiss = { askReopen = false })
        }
    }
}

// ---------------------------------------------------------------- one bill

@Composable
fun BillScreen(billId: String, nav: Nav, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val h = rememberLoad("bill", billId) { Graph.repo.bill(billId) }
    var record by remember { mutableStateOf<String?>(null) } // "in" or "out"
    val action = rememberAction()
    AppScreen(stringResource(R.string.bill), onBack = onBack) { pad ->
        Loaded(h) { full ->
            val b = full.bill
            val x = full.extras
            ScrollColumn(pad) {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.memberName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            SmallLabel(billTitle(b))
                        }
                        StatusChip(b.status)
                    }
                    if (x.isMine) BalanceText(b.remainingPaisa, big = true)
                    else InfoRow(stringResource(R.string.left_to_settle), money(b.remainingPaisa), strong = true)
                }
                SectionCard(stringResource(R.string.how_worked_out)) {
                    val d = b.detail
                    // Someone else's bill (not a manager): only the total and what is settled.
                    val full = x.isMine || x.canMark
                    if (!full) Hint(stringResource(R.string.others_bill_hint))
                    else if (b.kind == "month") {
                        InfoRow(stringResource(R.string.tea_line, (d.cups ?: 0) + (d.guestCups ?: 0), rate(d.teaRate)?.let { money(it) } ?: "—"), money(b.teaPaisa))
                        if ((d.guestCups ?: 0) > 0) Hint(stringResource(R.string.includes_guest_cups, d.guestCups ?: 0))
                        if (d.kitchenDays != null) InfoRow(stringResource(R.string.kitchen_line, d.kitchenDays, rate(d.perDay)?.let { money(it) } ?: "—"), money(b.kitchenSharedPaisa))
                        if (b.mealsPaisa > 0) InfoRow(pluralStringResource(R.plurals.meals_n, d.mealsCount ?: 0, d.mealsCount ?: 0), money(b.mealsPaisa))
                        x.meals?.forEach { ml -> Text("  " + fmtDate(ml.day) + " · " + mealLabel(ml.kind) + (if (ml.title.isNotBlank()) " · " + ml.title else "") + " — " + money(ml.sharePaisa),
                            style = MaterialTheme.typography.bodySmall) }
                        if (b.partyPaisa > 0) InfoRow(stringResource(R.string.parties), money(b.partyPaisa))
                    } else {
                        InfoRow(stringResource(R.string.party_share), money(b.partyPaisa))
                        b.partyDay?.let { InfoRow(stringResource(R.string.day), fmtDate(it)) }
                    }
                    if (full && b.creditPaisa > 0) {
                        InfoRow(stringResource(R.string.you_paid_for_group), "− " + money(b.creditPaisa), valueColor = LocalStatus.current.good)
                        x.paidForGroup.forEach { p ->
                            Text("  " + fmtDate(p.day) + " · " + p.title + " — " + money(p.amountPaisa), style = MaterialTheme.typography.bodySmall)
                            if (p.receivedPaisa > 0) Text("    " + stringResource(R.string.cash_from_non_members, money(p.receivedPaisa)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider()
                    InfoRow(stringResource(R.string.bill_total), money(b.totalPaisa), strong = true)
                    InfoRow(stringResource(R.string.settled_so_far), money(b.settledPaisa))
                    if (b.waitingPaisa > 0) InfoRow(stringResource(R.string.slip_waiting_amount), money(b.waitingPaisa))
                    InfoRow(stringResource(R.string.left_to_settle), money(b.remainingPaisa), strong = true)
                }
                if (x.payments.isNotEmpty()) SectionCard(stringResource(R.string.payments)) {
                    x.payments.forEachIndexed { i, p -> if (i > 0) HorizontalDivider(); PaymentLine(p) }
                }
                if (x.isMine && b.remainingPaisa > 0) PrimaryButton(stringResource(R.string.pay_this_bill), icon = Icons.Filled.Payments,
                    onClick = { nav.navigate(Routes.pay(b.placeId, b.id)) }, modifier = Modifier.testTag("pay_bill"))
                if (x.canMark) {
                    if (b.remainingPaisa > 0) PrimaryButton(stringResource(R.string.mark_paid_cash), icon = Icons.Filled.Check,
                        onClick = { record = "in" }, modifier = Modifier.testTag("mark_paid"))
                    if (b.remainingPaisa < 0) PrimaryButton(stringResource(R.string.mark_paid_back), icon = Icons.AutoMirrored.Filled.Undo, onClick = { record = "out" })
                    if (b.waitingPaisa > 0) SecondaryButton(stringResource(R.string.check_slips), onClick = { nav.navigate(Routes.slips(b.placeId)) })
                    Hint(stringResource(R.string.only_manager_marks))
                } else Hint(stringResource(R.string.bill_paid_when_marked))
                SecondaryButton(stringResource(R.string.ask_about_bill), icon = Icons.Filled.QuestionAnswer,
                    onClick = { nav.navigate(Routes.ask(b.placeId, "bill", b.id, b.month)) })
            }
            record?.let { dir ->
                RecordDialog(b, dir, onDismiss = { record = null }) { amount, method, note ->
                    action.run(success = ctx.getString(R.string.marked_paid)) {
                        Graph.repo.paymentRecord(b.placeId, b.id, b.memberId, dir, amount, method, null, note)
                        h.refresh()
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentLine(p: Payment) {
    var show by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (p.direction == "out") "↩ " else "") + money(p.amountPaisa) + " · " + methodLabel(p.method), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            StatusChip(p.status)
        }
        SmallLabel(fmtDateTime(p.createdAt) + (p.decidedByName?.let { " · " + stringResource(R.string.by_x, it) } ?: ""))
        if (p.note.isNotBlank()) Text(p.note, style = MaterialTheme.typography.bodySmall)
        p.reason?.let { Text(stringResource(R.string.sent_back_reason, it), color = LocalStatus.current.owe, style = MaterialTheme.typography.bodySmall) }
        if (p.slipPath != null) {
            TextButton(onClick = { show = !show }) { Text(stringResource(if (show) R.string.hide_slip else R.string.view_slip)) }
            if (show) StoredImage(p.slipPath)
        }
    }
}

private val METHODS = listOf("cash", "easypaisa", "jazzcash", "bank", "other")

@Composable
private fun RecordDialog(b: Bill, direction: String, onDismiss: () -> Unit, onSave: (Long, String, String) -> Unit) {
    var amount by rememberSaveable { mutableStateOf(Money.toInput(kotlin.math.abs(b.remainingPaisa))) }
    var method by rememberSaveable { mutableStateOf("cash") }
    var note by rememberSaveable { mutableStateOf("") }
    val paisa = Money.parse(amount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (direction == "in") R.string.mark_paid_cash else R.string.mark_paid_back)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (direction == "in") R.string.record_in_body else R.string.record_out_body, b.memberName))
                AmountField(amount, { amount = it }, stringResource(R.string.amount))
                ChoiceRow(METHODS.map { it to methodLabel(it) }, method, { method = it })
                TextInput(note, { note = it.take(200) }, stringResource(R.string.note_optional))
            }
        },
        confirmButton = { TextButton(enabled = paisa != null && paisa > 0, onClick = { onDismiss(); onSave(paisa!!, method, note.trim()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---------------------------------------------------------------- paying

/** A member pays: amount, how, and a slip photo. The bill shows paid only after the manager marks it. */
@Composable
fun PayScreen(placeId: String, billId: String?, onBack: () -> Unit) {
    val h = rememberLoad("pay", placeId, billId) { if (billId == null) Bill(id = "") else Graph.repo.bill(billId).bill }
    AppScreen(stringResource(R.string.pay), onBack = onBack) { pad ->
        if (billId != null) Loaded(h) { b -> PayForm(placeId, b, pad, onBack) }
        else PayForm(placeId, null, pad, onBack)
    }
}

@Composable
private fun PayForm(placeId: String, b: Bill?, pad: androidx.compose.foundation.layout.PaddingValues, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var amount by rememberSaveable(b?.id) { mutableStateOf(b?.let { Money.toInput(maxOf(0, it.remainingPaisa)) } ?: "") }
    var method by rememberSaveable { mutableStateOf("easypaisa") }
    var slip by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    val action = rememberAction()
    val paisa = Money.parse(amount)
    ScrollColumn(pad) {
        if (b != null) SectionCard {
            Text(billTitle(b), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            InfoRow(stringResource(R.string.left_to_settle), money(b.remainingPaisa), strong = true)
        } else Hint(stringResource(R.string.advance_hint))
        AmountField(amount, { amount = it }, stringResource(R.string.amount_paid), modifier = Modifier.testTag("pay_amount"))
        Text(stringResource(R.string.paid_how), style = MaterialTheme.typography.labelLarge)
        ChoiceRow(METHODS.map { it to methodLabel(it) }, method, { method = it })
        PhotoUpload(placeId, "slips", slip, { slip = it }, stringResource(R.string.payment_slip))
        TextInput(note, { note = it.take(200) }, stringResource(R.string.note_optional), supporting = stringResource(R.string.pay_note_hint))
        PrimaryButton(stringResource(R.string.send_to_manager), enabled = paisa != null && paisa > 0, busy = action.busy, modifier = Modifier.testTag("pay_send"),
            onClick = {
                action.run(success = ctx.getString(R.string.payment_sent), after = { onBack() }) {
                    Graph.repo.paymentSend(placeId, b?.id, paisa!!, method, slip, note.trim())
                }
            })
        Hint(stringResource(R.string.bill_paid_when_marked))
    }
}

// ---------------------------------------------------------------- manager: slips and reminders

@Composable
fun SlipsScreen(placeId: String, nav: Nav, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val h = rememberLoad("slips", placeId) { Graph.repo.paymentsWaiting(placeId) }
    var sendBack by remember { mutableStateOf<WaitingPayment?>(null) }
    val action = rememberAction()
    AppScreen(stringResource(R.string.slips_waiting_title), onBack = onBack) { pad ->
        Loaded(h) { list ->
            ScrollColumn(pad) {
                if (list.isEmpty()) EmptyState(Icons.Filled.Payments, stringResource(R.string.no_slips))
                list.forEach { p ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.memberName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                SmallLabel((p.bill?.let { billTitle(it) } ?: stringResource(R.string.advance)) + " · " + fmtDateTime(p.createdAt))
                            }
                            Text(money(p.amountPaisa), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        InfoRow(stringResource(R.string.method), methodLabel(p.method))
                        p.bill?.let { InfoRow(stringResource(R.string.bill_left), money(it.remainingPaisa)) }
                        if (p.note.isNotBlank()) Text(p.note, style = MaterialTheme.typography.bodyMedium)
                        p.slipPath?.let { StoredImage(it) } ?: Hint(stringResource(R.string.no_slip_photo))
                        if (p.canMark) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton(stringResource(R.string.send_back), modifier = Modifier.weight(1f), onClick = { sendBack = p })
                            PrimaryButton(stringResource(R.string.accept_mark_paid), icon = Icons.Filled.Check, modifier = Modifier.weight(1.4f), busy = action.busy, onClick = {
                                action.run(success = ctx.getString(R.string.marked_paid)) { Graph.repo.paymentDecide(p.id, true, null, null); h.refresh() }
                            })
                        } else Hint(stringResource(R.string.cannot_mark_own))
                        p.billId?.let { id -> TextButton(onClick = { nav.navigate(Routes.bill(id)) }) { Text(stringResource(R.string.open_bill)) } }
                    }
                }
            }
            sendBack?.let { p ->
                ReasonDialog(stringResource(R.string.send_back), stringResource(R.string.send_back_body, p.memberName), stringResource(R.string.send_back),
                    minLength = 3, onConfirm = { r -> action.run { Graph.repo.paymentDecide(p.id, false, null, r); h.refresh() } }, onDismiss = { sendBack = null })
            }
        }
    }
}

@Composable
fun RemindScreen(placeId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val h = rememberLoad("remind", placeId) { Graph.repo.bills(placeId, null, unpaid = true) }
    val picked = remember { mutableStateListOf<String>() }
    var note by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    val action = rememberAction()
    AppScreen(stringResource(R.string.send_reminders), onBack = onBack) { pad ->
        Loaded(h) { bills ->
            val byMember = bills.filter { it.remainingPaisa > 0 }.groupBy { it.memberId }
            ScrollColumn(pad) {
                if (byMember.isEmpty()) EmptyState(Icons.Filled.Check, stringResource(R.string.nobody_pending))
                else {
                    Row {
                        TextButton(onClick = { picked.clear(); picked.addAll(byMember.keys) }) { Text(stringResource(R.string.select_all)) }
                        TextButton(onClick = { picked.clear() }) { Text(stringResource(R.string.select_none)) }
                    }
                    SectionCard {
                        byMember.forEach { (id, list) ->
                            Row(Modifier.fillMaxWidth().clickable { if (id in picked) picked.remove(id) else picked.add(id) }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(id in picked, { if (it) picked.add(id) else picked.remove(id) })
                                Column(Modifier.weight(1f)) {
                                    Text(list.first().memberName, fontWeight = FontWeight.SemiBold)
                                    SmallLabel(list.map { billTitle(it) }.joinToString(", "))
                                }
                                Text(money(list.sumOf { it.remainingPaisa }), color = LocalStatus.current.owe, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    TextInput(note, { note = it.take(200) }, stringResource(R.string.reminder_note_optional), singleLine = false, minLines = 2)
                    PrimaryButton(stringResource(R.string.send_reminder_n, picked.size), enabled = picked.isNotEmpty(), busy = action.busy, onClick = {
                        action.run {
                            val r = Graph.repo.remind(placeId, picked.toList(), note.trim())
                            result = ctx.getString(R.string.reminders_result, r.sent, r.skipped)
                        }
                    }, modifier = Modifier.testTag("send_remind"))
                    Hint(stringResource(R.string.remind_once_a_day))
                    result?.let { Text(it, color = LocalStatus.current.good, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}
