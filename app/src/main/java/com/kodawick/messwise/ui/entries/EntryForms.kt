package com.kodawick.messwise.ui.entries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kodawick.messwise.R
import com.kodawick.messwise.core.Money
import com.kodawick.messwise.data.Graph
import com.kodawick.messwise.data.model.Entry
import com.kodawick.messwise.data.model.Member
import com.kodawick.messwise.data.model.Place
import com.kodawick.messwise.ui.common.AmountField
import com.kodawick.messwise.ui.common.AppScreen
import com.kodawick.messwise.ui.common.ChoiceRow
import com.kodawick.messwise.ui.common.DateField
import com.kodawick.messwise.ui.common.Hint
import com.kodawick.messwise.ui.common.InfoRow
import com.kodawick.messwise.ui.common.Loaded
import com.kodawick.messwise.ui.common.PhotoUpload
import com.kodawick.messwise.ui.common.PrimaryButton
import com.kodawick.messwise.ui.common.ReasonDialog
import com.kodawick.messwise.ui.common.ScrollColumn
import com.kodawick.messwise.ui.common.SectionCard
import com.kodawick.messwise.ui.common.SmallLabel
import com.kodawick.messwise.ui.common.TextInput
import com.kodawick.messwise.ui.common.money
import com.kodawick.messwise.ui.common.rememberAction
import com.kodawick.messwise.ui.common.rememberLoad
import com.kodawick.messwise.ui.common.todayPk
import com.kodawick.messwise.ui.theme.LocalStatus

private class FormData(val place: Place, val members: List<Member>, val entry: Entry?)

@Composable
private fun rememberForm(placeId: String, type: String, id: String?) = rememberLoad("form", placeId, type, id) {
    FormData(Graph.repo.place(placeId), Graph.repo.members(placeId), id?.let { Graph.repo.entry(type, it) })
}

/** Save now for a new entry; for an edit, ask for the reason first (kept in the history). */
@Composable
private fun SaveButton(editing: Boolean, enabled: Boolean, busy: Boolean, onSave: (reason: String?) -> Unit) {
    var ask by remember { mutableStateOf(false) }
    PrimaryButton(stringResource(if (editing) R.string.save_changes else R.string.save), enabled = enabled, busy = busy,
        modifier = Modifier.testTag("entry_save"), onClick = { if (editing) ask = true else onSave(null) })
    if (ask) ReasonDialog(stringResource(R.string.why_change), stringResource(R.string.why_change_body), stringResource(R.string.save_changes),
        minLength = 3, danger = false, onConfirm = { onSave(it) }, onDismiss = { ask = false })
}

// ---------------------------------------------------------------- purchases

/** Something bought for the Tea Bill (milk, tea, sugar) or the Kitchen Bill (oil, flour, gas). */
@Composable
fun PurchaseFormScreen(placeId: String, id: String?, bill0: String, onBack: () -> Unit) {
    val h = rememberForm(placeId, "purchase", id)
    AppScreen(stringResource(if (id == null) R.string.new_purchase else R.string.edit_purchase), onBack = onBack) { pad ->
        Loaded(h) { f -> PurchaseForm(f, bill0, onBack, pad) }
    }
}

@Composable
private fun PurchaseForm(f: FormData, bill0: String, onBack: () -> Unit, pad: androidx.compose.foundation.layout.PaddingValues) {
    val ctx = LocalContext.current
    val e = f.entry
    var bill by rememberSaveable { mutableStateOf(e?.bill ?: bill0) }
    var item by rememberSaveable { mutableStateOf(e?.item ?: "") }
    var amount by rememberSaveable { mutableStateOf(e?.let { Money.toInput(it.amountPaisa) } ?: "") }
    var day by rememberSaveable { mutableStateOf(e?.day ?: todayPk().toString()) }
    var note by rememberSaveable { mutableStateOf(e?.note ?: "") }
    var receipt by rememberSaveable { mutableStateOf(e?.receiptPath) }
    val members = pickable(f.members, e?.payers?.map { it.memberId }?.toSet() ?: emptySet())
    val payers = remember { mutableStateListOf<PayerInput>().apply {
        if (e != null) e.payers.forEach { add(PayerInput(it.memberId, Money.toInput(it.amountPaisa))) } else add(PayerInput(f.place.me.id))
    } }
    val total = Money.parse(amount)
    val payersOk = total != null && total > 0 && payersJson(payers, total) != null
    val action = rememberAction()
    val teaItems = listOf(R.string.item_milk, R.string.item_tea, R.string.item_sugar, R.string.item_gas)
    val kitchenItems = listOf(R.string.item_oil, R.string.item_flour, R.string.item_rice, R.string.item_vegetables, R.string.item_spices, R.string.item_gas_cylinder, R.string.item_cleaning)
    ScrollColumn(pad) {
        SmallLabel(stringResource(R.string.which_bill))
        ChoiceRow(listOf("tea" to stringResource(R.string.tea_bill), "kitchen" to stringResource(R.string.kitchen_bill)), bill, { bill = it })
        Hint(stringResource(if (bill == "tea") R.string.tea_bill_hint else R.string.kitchen_bill_hint))
        TextInput(item, { item = it.take(80) }, stringResource(R.string.what_bought), modifier = Modifier.testTag("purchase_item"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (if (bill == "tea") teaItems else kitchenItems).forEach { r ->
                val label = stringResource(r)
                AssistChip(onClick = { item = label }, label = { Text(label) })
            }
        }
        AmountField(amount, { amount = it }, stringResource(R.string.total_amount), modifier = Modifier.testTag("purchase_amount"))
        DateField(day, { day = it }, stringResource(R.string.bought_on))
        PayersEditor(members, payers, total)
        PhotoUpload(f.place.id, "receipts", receipt, { receipt = it }, stringResource(R.string.receipt_photo))
        TextInput(note, { note = it.take(300) }, stringResource(R.string.note_optional), singleLine = false, minLines = 2)
        SaveButton(e != null, item.isNotBlank() && payersOk, action.busy) { reason ->
            action.run(success = ctx.getString(R.string.saved), after = { onBack() }) {
                Graph.repo.purchaseSave(mapOf(
                    "id" to e?.id, "place_id" to f.place.id, "bill" to bill, "item" to item.trim(), "amount_paisa" to total,
                    "bought_on" to day, "note" to note.trim(), "receipt_path" to receipt, "payers" to payersJson(payers, total!!), "reason" to reason,
                ))
            }
        }
    }
}

// ---------------------------------------------------------------- meals

/** A meal bill (lunch order, dinner): split only among those who ate, by plates. */
@Composable
fun MealFormScreen(placeId: String, id: String?, onBack: () -> Unit) {
    val h = rememberForm(placeId, "meal", id)
    AppScreen(stringResource(if (id == null) R.string.new_meal else R.string.edit_meal), onBack = onBack) { pad ->
        Loaded(h) { f -> MealForm(f, onBack, pad) }
    }
}

private fun peopleFrom(members: List<Member>, e: Entry?, useHeads: Boolean): List<PersonInput> = members.map { m ->
    val mine = e?.people?.firstOrNull { it.subject == "member" && it.memberId == m.id }
    val guest = e?.people?.filter { it.subject == "personal_guest" && it.memberId == m.id }
        ?.sumOf { if (useHeads) it.heads else it.units.toInt() } ?: 0
    PersonInput(m.id, selected = mine != null, units = mine?.let { if (useHeads) it.heads else it.units.toInt().coerceAtLeast(1) } ?: 1, guests = guest)
}

private fun officeFrom(e: Entry?, useHeads: Boolean) =
    e?.people?.filter { it.subject == "office_guest" }?.sumOf { if (useHeads) it.heads else it.units.toInt() } ?: 0

@Composable
private fun MealForm(f: FormData, onBack: () -> Unit, pad: androidx.compose.foundation.layout.PaddingValues) {
    val ctx = LocalContext.current
    val e = f.entry
    var kind by rememberSaveable { mutableStateOf(e?.kind ?: "lunch") }
    var title by rememberSaveable { mutableStateOf(e?.title ?: "") }
    var amount by rememberSaveable { mutableStateOf(e?.let { Money.toInput(it.amountPaisa) } ?: "") }
    var day by rememberSaveable { mutableStateOf(e?.day ?: todayPk().toString()) }
    var note by rememberSaveable { mutableStateOf(e?.note ?: "") }
    var receipt by rememberSaveable { mutableStateOf(e?.receiptPath) }
    val keep = (e?.people?.mapNotNull { it.memberId } ?: emptyList()).toSet() + (e?.payers?.map { it.memberId } ?: emptyList())
    val members = pickable(f.members, keep)
    val eaters = pickable(f.members, keep).filter { it.onKitchen || it.onTea }
    val people = remember { mutableStateListOf<PersonInput>().apply { addAll(peopleFrom(eaters, e, useHeads = false)) } }
    var office by remember { mutableIntStateOf(officeFrom(e, useHeads = false)) }
    val payers = remember { mutableStateListOf<PayerInput>().apply {
        if (e != null) e.payers.forEach { add(PayerInput(it.memberId, Money.toInput(it.amountPaisa))) } else add(PayerInput(f.place.me.id))
    } }
    val total = Money.parse(amount)
    val units = people.filter { it.selected }.sumOf { it.units + it.guests } + office
    val ok = total != null && total > 0 && people.any { it.selected } && payersJson(payers, total) != null
    val action = rememberAction()
    ScrollColumn(pad) {
        ChoiceRow(listOf("breakfast" to stringResource(R.string.meal_breakfast), "lunch" to stringResource(R.string.meal_lunch),
            "dinner" to stringResource(R.string.meal_dinner), "other" to stringResource(R.string.meal_other)), kind, { kind = it })
        TextInput(title, { title = it.take(80) }, stringResource(R.string.meal_what_optional))
        AmountField(amount, { amount = it }, stringResource(R.string.total_bill), modifier = Modifier.testTag("meal_amount"))
        DateField(day, { day = it }, stringResource(R.string.day))
        PeoplePicker(eaters, people, showUnits = true, officeGuests = office, onOfficeGuests = { office = it }, title = stringResource(R.string.who_ate))
        if (total != null && units > 0) SectionCard {
            InfoRow(stringResource(R.string.plates_total), units.toString())
            InfoRow(stringResource(R.string.per_plate), money(total / units), strong = true)
            Hint(stringResource(R.string.meal_split_hint))
        }
        PayersEditor(members, payers, total)
        PhotoUpload(f.place.id, "receipts", receipt, { receipt = it }, stringResource(R.string.receipt_photo))
        TextInput(note, { note = it.take(300) }, stringResource(R.string.note_optional), singleLine = false, minLines = 2)
        SaveButton(e != null, ok, action.busy) { reason ->
            val list = people.filter { it.selected }.flatMap { p ->
                listOf(mapOf("subject" to "member", "member_id" to p.memberId, "units" to p.units)) +
                    if (p.guests > 0) listOf(mapOf("subject" to "personal_guest", "member_id" to p.memberId, "units" to p.guests)) else emptyList()
            } + if (office > 0) listOf(mapOf("subject" to "office_guest", "member_id" to null, "units" to office)) else emptyList()
            action.run(success = ctx.getString(R.string.saved), after = { onBack() }) {
                Graph.repo.mealSave(mapOf(
                    "id" to e?.id, "place_id" to f.place.id, "kind" to kind, "title" to title.trim(), "amount_paisa" to total, "day" to day,
                    "note" to note.trim(), "receipt_path" to receipt, "payers" to payersJson(payers, total!!), "people" to list, "reason" to reason,
                ))
            }
        }
    }
}

// ---------------------------------------------------------------- parties

/** A one-time party or grand lunch/dinner: total, who attended, share per head, paid or pending. */
@Composable
fun PartyFormScreen(placeId: String, id: String?, onBack: () -> Unit) {
    val h = rememberForm(placeId, "party", id)
    AppScreen(stringResource(if (id == null) R.string.new_party else R.string.edit_party), onBack = onBack) { pad ->
        Loaded(h) { f -> PartyForm(f, onBack, pad) }
    }
}

@Composable
private fun PartyForm(f: FormData, onBack: () -> Unit, pad: androidx.compose.foundation.layout.PaddingValues) {
    val ctx = LocalContext.current
    val e = f.entry
    val s = LocalStatus.current
    var title by rememberSaveable { mutableStateOf(e?.title ?: "") }
    var amount by rememberSaveable { mutableStateOf(e?.let { Money.toInput(it.amountPaisa) } ?: "") }
    var day by rememberSaveable { mutableStateOf(e?.day ?: todayPk().toString()) }
    var note by rememberSaveable { mutableStateOf(e?.note ?: "") }
    var receipt by rememberSaveable { mutableStateOf(e?.receiptPath) }
    var officeSplit by rememberSaveable { mutableStateOf(e?.officeSplit ?: f.place.partyOfficeSplit) }
    var addToMonth by rememberSaveable { mutableStateOf(e?.addToMonth ?: false) }
    val keep = (e?.people?.mapNotNull { it.memberId } ?: emptyList()).toSet() + (e?.payers?.map { it.memberId } ?: emptyList())
    val members = pickable(f.members, keep)
    val people = remember { mutableStateListOf<PersonInput>().apply { addAll(peopleFrom(members, e, useHeads = true)) } }
    var office by remember { mutableIntStateOf(officeFrom(e, useHeads = true)) }
    val payers = remember { mutableStateListOf<PayerInput>().apply {
        if (e != null) e.payers.forEach { add(PayerInput(it.memberId, Money.toInput(it.amountPaisa))) } else add(PayerInput(f.place.me.id))
    } }
    val total = Money.parse(amount)
    val heads = people.filter { it.selected }.sumOf { 1 + it.guests } + office
    val ok = title.isNotBlank() && total != null && total > 0 && people.any { it.selected } && payersJson(payers, total) != null
    val action = rememberAction()
    ScrollColumn(pad) {
        TextInput(title, { title = it.take(80) }, stringResource(R.string.party_title), supporting = stringResource(R.string.party_title_hint),
            modifier = Modifier.testTag("party_title"))
        AmountField(amount, { amount = it }, stringResource(R.string.total_expenses), modifier = Modifier.testTag("party_amount"))
        DateField(day, { day = it }, stringResource(R.string.day))
        PeoplePicker(members, people, showUnits = false, officeGuests = office, onOfficeGuests = { office = it }, title = stringResource(R.string.who_attended))
        if (office > 0) SectionCard(stringResource(R.string.office_guests_paid_by)) {
            ChoiceRow(listOf("all" to stringResource(R.string.split_everyone), "attendees" to stringResource(R.string.split_attendees)), officeSplit, { officeSplit = it })
        }
        SectionCard {
            InfoRow(stringResource(R.string.persons_attended), heads.toString())
            if (total != null && heads > 0) InfoRow(stringResource(R.string.share_per_head), money(total / heads), strong = true)
            if (people.any { it.selected && it.guests > 0 }) Text(stringResource(R.string.guest_heads_to_host), style = MaterialTheme.typography.bodySmall, color = s.info)
        }
        SwitchRow(stringResource(R.string.add_to_month_bill), stringResource(if (addToMonth) R.string.add_to_month_on else R.string.add_to_month_off), addToMonth) { addToMonth = it }
        PayersEditor(members, payers, total)
        PhotoUpload(f.place.id, "receipts", receipt, { receipt = it }, stringResource(R.string.receipt_photo))
        TextInput(note, { note = it.take(300) }, stringResource(R.string.note_optional), singleLine = false, minLines = 2)
        SaveButton(e != null, ok, action.busy) { reason ->
            val list = people.filter { it.selected }.flatMap { p ->
                listOf(mapOf("subject" to "member", "member_id" to p.memberId, "heads" to 1)) +
                    if (p.guests > 0) listOf(mapOf("subject" to "personal_guest", "member_id" to p.memberId, "heads" to p.guests)) else emptyList()
            } + if (office > 0) listOf(mapOf("subject" to "office_guest", "member_id" to null, "heads" to office)) else emptyList()
            action.run(success = ctx.getString(R.string.saved), after = { onBack() }) {
                Graph.repo.partySave(mapOf(
                    "id" to e?.id, "place_id" to f.place.id, "title" to title.trim(), "amount_paisa" to total, "day" to day,
                    "office_split" to officeSplit, "add_to_month" to addToMonth, "note" to note.trim(), "receipt_path" to receipt,
                    "payers" to payersJson(payers, total!!), "people" to list, "reason" to reason,
                ))
            }
        }
        Text(stringResource(R.string.party_bills_note), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Normal)
    }
}
