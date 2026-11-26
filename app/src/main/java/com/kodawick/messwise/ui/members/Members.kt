package com.kodawick.messwise.ui.members

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import com.kodawick.messwise.data.Graph
import com.kodawick.messwise.data.model.JoinRequest
import com.kodawick.messwise.data.model.Member
import com.kodawick.messwise.ui.Nav
import com.kodawick.messwise.ui.Routes
import com.kodawick.messwise.ui.bills.BillRow
import com.kodawick.messwise.ui.common.AppScreen
import com.kodawick.messwise.ui.common.Avatar
import com.kodawick.messwise.ui.common.ChoiceRow
import com.kodawick.messwise.ui.common.ConfirmDialog
import com.kodawick.messwise.ui.common.DateField
import com.kodawick.messwise.ui.common.EmptyState
import com.kodawick.messwise.ui.common.Hint
import com.kodawick.messwise.ui.common.InfoRow
import com.kodawick.messwise.ui.common.Loaded
import com.kodawick.messwise.ui.common.MonthSwitcher
import com.kodawick.messwise.ui.common.PrimaryButton
import com.kodawick.messwise.ui.common.ReasonDialog
import com.kodawick.messwise.ui.common.ScrollColumn
import com.kodawick.messwise.ui.common.SecondaryButton
import com.kodawick.messwise.ui.common.SectionCard
import com.kodawick.messwise.ui.common.SmallLabel
import com.kodawick.messwise.ui.common.StatusChip
import com.kodawick.messwise.ui.common.TextInput
import com.kodawick.messwise.ui.common.fmtDate
import com.kodawick.messwise.ui.common.money
import com.kodawick.messwise.ui.common.rememberAction
import com.kodawick.messwise.ui.common.rememberLoad
import com.kodawick.messwise.ui.common.roleLabel
import com.kodawick.messwise.ui.common.thisMonth
import com.kodawick.messwise.ui.common.todayPk
import com.kodawick.messwise.ui.common.typeLabel
import com.kodawick.messwise.ui.entries.MemberDropdown
import com.kodawick.messwise.ui.places.TypeChoice

@Composable
fun MembersScreen(placeId: String, nav: Nav, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val place = rememberLoad("place", placeId, cached = { Graph.repo.placeCached(placeId) }) { Graph.repo.place(placeId) }
    val h = rememberLoad("members", placeId, cached = { Graph.repo.membersCached(placeId) }) { Graph.repo.members(placeId) }
    var showLeft by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val manage = place.data?.can?.manage == true
    val action = rememberAction()
    AppScreen(stringResource(R.string.members), onBack = onBack) { pad ->
        Loaded(h) { all ->
            val list = all.filter { showLeft || it.status == "active" }
            ScrollColumn(pad) {
                SmallLabel(stringResource(R.string.members_count, all.count { it.status == "active" }))
                if (manage) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.invite), icon = Icons.Filled.GroupAdd, modifier = Modifier.weight(1f), onClick = { nav.navigate(Routes.invite(placeId)) })
                    SecondaryButton(stringResource(R.string.add_without_app), icon = Icons.Filled.PersonAdd, modifier = Modifier.weight(1f), onClick = { adding = true })
                }
                SectionCard {
                    list.forEachIndexed { i, m ->
                        if (i > 0) HorizontalDivider()
                        MemberLine(m) { nav.navigate(Routes.member(m.id)) }
                    }
                    if (list.isEmpty()) Hint(stringResource(R.string.no_members))
                }
                if (all.any { it.status != "active" }) TextButton(onClick = { showLeft = !showLeft }) {
                    Text(stringResource(if (showLeft) R.string.hide_left else R.string.show_left))
                }
                Hint(stringResource(R.string.types_explained))
            }
        }
    }
    if (adding) AddMemberDialog(onDismiss = { adding = false }) { name, type ->
        action.run(success = ctx.getString(R.string.member_added)) { Graph.repo.memberAdd(placeId, name, type) }
    }
}

@Composable
private fun MemberLine(m: Member, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(m.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name + if (m.isMe) " (" + stringResource(R.string.me) + ")" else "", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            SmallLabel(roleLabel(m.role, m.isMainManager) + " · " + typeLabel(m.type) + if (!m.linked) " · " + stringResource(R.string.no_app) else "")
        }
        when {
            m.status != "active" -> StatusChip("left")
            m.onLeaveToday -> StatusChip("on_leave")
        }
    }
}

@Composable
private fun AddMemberDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("resident") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_without_app)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.add_without_app_body), style = MaterialTheme.typography.bodySmall)
                TextInput(name, { name = it.take(60) }, stringResource(R.string.name))
                TypeChoice(type) { type = it }
            }
        },
        confirmButton = { TextButton(enabled = name.trim().length >= 2, onClick = { onDismiss(); onAdd(name.trim(), type) }) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** One member: this month so far, bills, leave, and (for managers) role and type. */
@Composable
fun MemberScreen(memberId: String, nav: Nav, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var month by rememberSaveable { mutableStateOf(thisMonth()) }
    val h = rememberLoad("member", memberId, month) { Graph.repo.member(memberId, month) }
    val pid = remember { Graph.prefs.lastPlaceId ?: "" }
    val ph = rememberLoad("place", pid, cached = { Graph.repo.placeCached(pid) }) { Graph.repo.place(pid) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val action = rememberAction()
    AppScreen(stringResource(R.string.member), onBack = onBack) { pad ->
        Loaded(h) { full ->
            val m = full.member
            val x = full.extras
            val place = ph.data
            val manage = place?.can?.manage == true
            val owner = place?.can?.owner == true
            ScrollColumn(pad) {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(m.name, 52)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            SmallLabel(roleLabel(m.role, m.isMainManager) + " · " + typeLabel(m.type))
                        }
                        if (m.status != "active") StatusChip("left") else if (m.onLeaveToday) StatusChip("on_leave")
                    }
                    m.joinedOn?.let { InfoRow(stringResource(R.string.joined_on), fmtDate(it)) }
                    m.leftOn?.let { InfoRow(stringResource(R.string.left_on), fmtDate(it)) }
                    InfoRow(stringResource(R.string.on_tea_bill), stringResource(if (m.onTea) R.string.yes else R.string.no))
                    InfoRow(stringResource(R.string.on_kitchen_bill), stringResource(if (m.onKitchen) R.string.yes else R.string.no))
                    if (!m.linked) Hint(stringResource(R.string.member_no_app_hint))
                }
                MonthSwitcher(month) { month = it }
                x.monthRow?.let { r ->
                    SectionCard(stringResource(R.string.this_month)) {
                        InfoRow(stringResource(R.string.row_tea, r.cups + r.guestCups, ""), money(r.teaPaisa))
                        r.kitchenDays?.let { InfoRow(stringResource(R.string.row_kitchen, it, ""), money(r.kitchenSharedPaisa)) }
                        if (r.mealsPaisa > 0) InfoRow(stringResource(R.string.row_meals, r.mealsCount, ""), money(r.mealsPaisa))
                        if (r.partyPaisa > 0) InfoRow(stringResource(R.string.parties), money(r.partyPaisa))
                        if (r.creditPaisa > 0) InfoRow(stringResource(R.string.i_paid_for_group), "− " + money(r.creditPaisa))
                        InfoRow(stringResource(R.string.so_far_total), money(r.totalPaisa), strong = true)
                    }
                }
                if (x.bills.isNotEmpty()) SectionCard(stringResource(R.string.bills)) {
                    x.bills.forEach { b -> BillRow(b, showName = false, onClick = { nav.navigate(Routes.bill(b.id)) }) }
                }
                SectionCard(stringResource(R.string.leave)) {
                    m.leave?.let { l ->
                        Text(stringResource(R.string.on_leave_from, fmtDate(l.fromDate), l.toDate?.let { fmtDate(it) } ?: stringResource(R.string.until_back)),
                            fontWeight = FontWeight.SemiBold)
                        if (manage || m.isMe) TextButton(onClick = { dialog = "end_leave" }) { Text(stringResource(R.string.back_from_leave)) }
                    }
                    x.leaves.filter { it.id != m.leave?.id }.forEach { l ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fmtDate(l.fromDate) + " – " + (l.toDate?.let { fmtDate(it) } ?: "…") + if (l.note.isNotBlank()) " · " + l.note else "", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall)
                            if (manage) TextButton(onClick = { dialog = "void_leave:" + l.id }) { Text(stringResource(R.string.remove)) }
                        }
                    }
                    if (m.status == "active" && m.leave == null && (manage || m.isMe)) SecondaryButton(stringResource(R.string.mark_leave), icon = Icons.Filled.BeachAccess,
                        onClick = { dialog = "leave" })
                    Hint(stringResource(R.string.leave_hint))
                }
                if (m.status == "active" && (manage || m.isMe)) SecondaryButton(stringResource(R.string.edit_member), icon = Icons.Filled.Edit, onClick = { dialog = "edit" })
                if (m.status == "active" && manage && !m.isMe && m.role != "owner") {
                    SecondaryButton(stringResource(R.string.role_and_rights), icon = Icons.Filled.Shield, onClick = { dialog = "role" })
                    SecondaryButton(stringResource(R.string.mark_left), icon = Icons.AutoMirrored.Filled.Logout, onClick = { dialog = "left" })
                }
                if (m.status == "active" && owner && !m.isMe && m.linked) TextButton(onClick = { dialog = "owner" }) { Text(stringResource(R.string.make_owner)) }
            }
            when (val d = dialog) {
                "edit" -> EditMemberDialog(m, onDismiss = { dialog = null }) { name, type ->
                    action.run(success = ctx.getString(R.string.saved)) { Graph.repo.memberUpdate(m.id, name, type); h.refresh() }
                }
                "role" -> RoleDialog(m, onDismiss = { dialog = null }) { role, main ->
                    action.run(success = ctx.getString(R.string.saved)) { Graph.repo.memberRole(m.id, role, main); h.refresh() }
                }
                "leave" -> LeaveDialog(onDismiss = { dialog = null }) { from, to, note ->
                    action.run(success = ctx.getString(R.string.leave_marked)) { Graph.repo.leaveAdd(m.id, from, to, note); h.refresh() }
                }
                "end_leave" -> DateDialog(stringResource(R.string.back_from_leave), stringResource(R.string.back_on), onDismiss = { dialog = null }) { day ->
                    action.run { m.leave?.let { Graph.repo.leaveEnd(it.id, day) }; h.refresh() }
                }
                "left" -> LeftDialog(m, onDismiss = { dialog = null }) { day, writeOff ->
                    action.run(success = ctx.getString(R.string.marked_left)) { Graph.repo.memberLeavePlace(m.id, day, writeOff); h.refresh() }
                }
                "owner" -> ConfirmDialog(stringResource(R.string.make_owner), stringResource(R.string.make_owner_body, m.name), stringResource(R.string.make_owner),
                    danger = true, onConfirm = { action.run { Graph.repo.ownerTransfer(m.id); h.refresh() } }, onDismiss = { dialog = null })
                null -> {}
                else -> if (d.startsWith("void_leave:")) ReasonDialog(stringResource(R.string.remove_leave), null, stringResource(R.string.remove), minLength = 3,
                    onConfirm = { r -> action.run { Graph.repo.leaveVoid(d.removePrefix("void_leave:"), r); h.refresh() } }, onDismiss = { dialog = null })
            }
        }
    }
}

@Composable
private fun EditMemberDialog(m: Member, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(m.name) }
    var type by rememberSaveable { mutableStateOf(m.type) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.edit_member)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(name, { name = it.take(60) }, stringResource(R.string.name))
            TypeChoice(type) { type = it }
            Hint(stringResource(R.string.type_change_hint))
        } },
        confirmButton = { TextButton(enabled = name.trim().length >= 2, onClick = { onDismiss(); onSave(name.trim(), type) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun RoleDialog(m: Member, onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var role by rememberSaveable { mutableStateOf(if (m.role == "manager" && m.isMainManager) "main" else m.role) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.role_and_rights)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceRow(listOf("member" to stringResource(R.string.role_member), "manager" to stringResource(R.string.role_deputy),
                "main" to stringResource(R.string.role_manager)), role, { role = it })
            Hint(when (role) {
                "main" -> stringResource(R.string.role_main_hint)
                "manager" -> stringResource(R.string.role_deputy_hint)
                else -> stringResource(R.string.role_member_hint)
            })
        } },
        confirmButton = { TextButton(onClick = { onDismiss(); onSave(if (role == "main") "manager" else role, role == "main") }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun LeaveDialog(onDismiss: () -> Unit, onSave: (String, String?, String) -> Unit) {
    var from by rememberSaveable { mutableStateOf(todayPk().toString()) }
    var to by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.mark_leave)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField(from, { from = it }, stringResource(R.string.leave_from))
            DateField(to, { to = it }, stringResource(R.string.leave_to_optional))
            if (to.isNotEmpty()) TextButton(onClick = { to = "" }) { Text(stringResource(R.string.until_back)) }
            TextInput(note, { note = it.take(120) }, stringResource(R.string.note_optional))
            Hint(stringResource(R.string.leave_hint))
        } },
        confirmButton = { TextButton(onClick = { onDismiss(); onSave(from, to.ifEmpty { null }, note.trim()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun DateDialog(title: String, label: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var day by rememberSaveable { mutableStateOf(todayPk().toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { DateField(day, { day = it }, label) },
        confirmButton = { TextButton(onClick = { onDismiss(); onSave(day) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun LeftDialog(m: Member, onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var day by rememberSaveable { mutableStateOf(todayPk().toString()) }
    var writeOff by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.mark_left)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.mark_left_body, m.name))
            DateField(day, { day = it }, stringResource(R.string.left_on))
            TextInput(writeOff, { writeOff = it.take(200) }, stringResource(R.string.write_off_optional), supporting = stringResource(R.string.write_off_hint))
        } },
        confirmButton = { TextButton(onClick = { onDismiss(); onSave(day, writeOff.trim().ifEmpty { null }) }) {
            Text(stringResource(R.string.mark_left), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** People who asked to join with the code. Approve as a type, or link them to a member added without the app. */
@Composable
fun JoinRequestsScreen(placeId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val h = rememberLoad("requests", placeId) { Graph.repo.joinRequests(placeId) }
    val members = rememberLoad("members", placeId, cached = { Graph.repo.membersCached(placeId) }) { Graph.repo.members(placeId) }
    var approving by remember { mutableStateOf<JoinRequest?>(null) }
    val action = rememberAction()
    AppScreen(stringResource(R.string.join_requests), onBack = onBack) { pad ->
        Loaded(h) { list ->
            ScrollColumn(pad) {
                if (list.isEmpty()) EmptyState(Icons.Filled.GroupAdd, stringResource(R.string.no_requests))
                list.forEach { r ->
                    SectionCard {
                        Text(r.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        r.email?.let { SmallLabel(it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton(stringResource(R.string.reject), modifier = Modifier.weight(1f), onClick = {
                                action.run { h.data = Graph.repo.joinDecide(r.id, false, "resident", null) }
                            })
                            PrimaryButton(stringResource(R.string.approve), modifier = Modifier.weight(1f).testTag("approve_${r.id}"), onClick = { approving = r })
                        }
                    }
                }
            }
        }
    }
    approving?.let { r ->
        var type by rememberSaveable { mutableStateOf("resident") }
        var link by rememberSaveable { mutableStateOf<String?>(null) }
        val unlinked = members.data?.filter { !it.linked && it.status == "active" } ?: emptyList()
        AlertDialog(onDismissRequest = { approving = null }, title = { Text(stringResource(R.string.approve_x, r.fullName)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (unlinked.isNotEmpty()) {
                    SmallLabel(stringResource(R.string.link_to_existing))
                    MemberDropdown(unlinked, link) { link = it }
                    if (link != null) TextButton(onClick = { link = null }) { Text(stringResource(R.string.new_member_instead)) }
                }
                if (link == null) TypeChoice(type) { type = it }
            } },
            confirmButton = { TextButton(onClick = {
                approving = null
                action.run(success = ctx.getString(R.string.member_added)) { h.data = Graph.repo.joinDecide(r.id, true, type, link) }
            }) { Text(stringResource(R.string.approve)) } },
            dismissButton = { TextButton(onClick = { approving = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
