package com.kodawick.messwise.ui.entries

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kodawick.messwise.R
import com.kodawick.messwise.core.Money
import com.kodawick.messwise.data.model.Member
import com.kodawick.messwise.ui.common.AmountField
import com.kodawick.messwise.ui.common.Hint
import com.kodawick.messwise.ui.common.SectionCard
import com.kodawick.messwise.ui.common.SmallLabel
import com.kodawick.messwise.ui.common.StatusChip
import com.kodawick.messwise.ui.common.Stepper
import com.kodawick.messwise.ui.common.money
import com.kodawick.messwise.ui.theme.LocalStatus

/** One person who paid for an entry. [amount] is the typed text (only used when several people paid). */
data class PayerInput(val memberId: String, val amount: String = "")

/** Builds the payers list the server expects; null when the amounts do not add up. */
fun payersJson(payers: List<PayerInput>, totalPaisa: Long): List<Map<String, Any?>>? {
    if (payers.isEmpty()) return emptyList()
    if (payers.size == 1) return listOf(mapOf("member_id" to payers[0].memberId, "amount_paisa" to totalPaisa))
    val list = payers.map { it.memberId to (Money.parse(it.amount) ?: return null) }
    if (list.any { it.second <= 0 } || list.sumOf { it.second } != totalPaisa) return null
    return list.map { mapOf("member_id" to it.first, "amount_paisa" to it.second) }
}

/** Who paid: one person by default, or several with their amounts. */
@Composable
fun PayersEditor(members: List<Member>, payers: SnapshotStateList<PayerInput>, totalPaisa: Long?) {
    val s = LocalStatus.current
    SectionCard(stringResource(R.string.who_paid)) {
        payers.forEachIndexed { i, p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemberDropdown(members, p.memberId, Modifier.weight(1f)) { id -> payers[i] = p.copy(memberId = id) }
                if (payers.size > 1) {
                    AmountField(p.amount, { payers[i] = p.copy(amount = it) }, stringResource(R.string.amount), Modifier.width(130.dp).padding(start = 8.dp))
                    IconButton(onClick = { payers.removeAt(i) }) { Icon(Icons.Filled.Close, stringResource(R.string.remove)) }
                }
            }
        }
        val free = members.filter { m -> payers.none { it.memberId == m.id } }
        if (free.isNotEmpty()) TextButton(onClick = { payers.add(PayerInput(free.first().id)) }) {
            Icon(Icons.Filled.PersonAdd, null); Text("  " + stringResource(R.string.add_payer))
        }
        if (payers.size > 1 && totalPaisa != null) {
            val sum = payers.sumOf { Money.parse(it.amount) ?: 0L }
            val left = totalPaisa - sum
            Text(if (left == 0L) stringResource(R.string.payers_match) else stringResource(R.string.payers_left, money(left)),
                color = if (left == 0L) s.good else s.owe, style = MaterialTheme.typography.bodySmall)
        }
        Hint(stringResource(R.string.payer_credit_hint))
    }
}

@Composable
fun MemberDropdown(members: List<Member>, selectedId: String?, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = members.firstOrNull { it.id == selectedId }?.name ?: stringResource(R.string.choose_person)
    Column(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(name, maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            members.forEach { m ->
                DropdownMenuItem(text = { Text(m.name + if (m.isMe) " (" + stringResource(R.string.me) + ")" else "") }, onClick = { onSelect(m.id); open = false })
            }
        }
    }
}

/** A person picked for a meal or party, with plates/heads and their own guests. */
data class PersonInput(val memberId: String, val selected: Boolean, val units: Int = 1, val guests: Int = 0)

/**
 * Tick who ate. [unitsLabel] is "plates" for meals and nothing for parties (one head each).
 * Each person can bring personal guests, who are charged to them.
 */
@Composable
fun PeoplePicker(
    members: List<Member>, people: SnapshotStateList<PersonInput>, showUnits: Boolean,
    officeGuests: Int, onOfficeGuests: (Int) -> Unit, title: String,
) {
    SectionCard(title) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { for (i in people.indices) people[i] = people[i].copy(selected = true) }) { Text(stringResource(R.string.select_all)) }
            TextButton(onClick = { for (i in people.indices) people[i] = people[i].copy(selected = false) }) { Text(stringResource(R.string.select_none)) }
        }
        people.forEachIndexed { i, p ->
            val m = members.firstOrNull { it.id == p.memberId } ?: return@forEachIndexed
            if (i > 0) HorizontalDivider()
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().clickable { people[i] = p.copy(selected = !p.selected) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(p.selected, { people[i] = p.copy(selected = it) })
                    Text(m.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (p.selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f))
                    if (m.onLeaveToday) StatusChip("on_leave")
                }
                if (p.selected) {
                    if (showUnits) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 48.dp)) {
                        SmallLabel(stringResource(R.string.plates), modifier = Modifier.weight(1f))
                        Stepper(p.units, { people[i] = p.copy(units = maxOf(1, it)) }, max = 50)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 48.dp)) {
                        SmallLabel(stringResource(R.string.their_guests), modifier = Modifier.weight(1f))
                        Stepper(p.guests, { people[i] = p.copy(guests = it) }, max = 100)
                    }
                }
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.office_guests), style = MaterialTheme.typography.bodyLarge)
                SmallLabel(stringResource(R.string.office_guests_split_hint))
            }
            Stepper(officeGuests, onOfficeGuests, max = 300)
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) SmallLabel(subtitle)
        }
        Switch(checked, onChange)
    }
}

/** Members who can be picked on a given entry: active, plus anyone already on it. */
fun pickable(all: List<Member>, keep: Set<String> = emptySet()) = all.filter { it.status == "active" || it.id in keep }
