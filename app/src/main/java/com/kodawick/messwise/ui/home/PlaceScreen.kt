package com.kodawick.messwise.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.EmojiFoodBeverage
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodawick.messwise.MainActivity
import com.kodawick.messwise.R
import com.kodawick.messwise.data.Graph
import com.kodawick.messwise.data.model.Dashboard
import com.kodawick.messwise.notify.Notify
import com.kodawick.messwise.ui.Nav
import com.kodawick.messwise.ui.Routes
import com.kodawick.messwise.ui.bills.BillRow
import com.kodawick.messwise.ui.bills.BillsTab
import com.kodawick.messwise.ui.common.AppScreen
import com.kodawick.messwise.ui.common.BalanceText
import com.kodawick.messwise.ui.common.BigNumber
import com.kodawick.messwise.ui.common.Hint
import com.kodawick.messwise.ui.common.InfoRow
import com.kodawick.messwise.ui.common.Loaded
import com.kodawick.messwise.ui.common.PrimaryButton
import com.kodawick.messwise.ui.common.ScrollColumn
import com.kodawick.messwise.ui.common.SectionCard
import com.kodawick.messwise.ui.common.SmallLabel
import com.kodawick.messwise.ui.common.fmtDate
import com.kodawick.messwise.ui.common.fmtMonth
import com.kodawick.messwise.ui.common.money
import com.kodawick.messwise.ui.common.plainError
import com.kodawick.messwise.ui.common.rememberLoad
import com.kodawick.messwise.ui.entries.EntriesTab
import com.kodawick.messwise.ui.more.MoreTab
import com.kodawick.messwise.ui.personal.PersonalTab
import com.kodawick.messwise.ui.theme.LocalStatus
import kotlinx.coroutines.launch

private data class Tab(val key: String, val label: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab("home", R.string.tab_home, Icons.Filled.Home),
    Tab("entries", R.string.tab_entries, Icons.AutoMirrored.Filled.ReceiptLong),
    Tab("bills", R.string.tab_bills, Icons.Filled.Payments),
    Tab("book", R.string.tab_book, Icons.AutoMirrored.Filled.MenuBook),
    Tab("more", R.string.tab_more, Icons.Filled.Menu),
)

/** One office or hostel: Home, Entries, Bills, My book (private) and More. */
@Composable
fun PlaceScreen(placeId: String, nav: Nav, activity: MainActivity, onThemeChanged: (String) -> Unit) {
    val h = rememberLoad("dash", placeId, cached = { Graph.repo.dashboardCached(placeId) }) { Graph.repo.dashboard(placeId) }
    var tab by rememberSaveable { mutableStateOf("home") }
    AskNotificationPermission()
    val d = h.data
    val title = when (tab) {
        "book" -> stringResource(R.string.my_book)
        else -> d?.place?.name ?: stringResource(R.string.app_name)
    }
    AppScreen(
        title = title,
        onBack = null,
        actions = {
            IconButton(onClick = { nav.navigate("places") }) { Icon(Icons.Filled.SwapHoriz, stringResource(R.string.switch_place)) }
            IconButton(onClick = { nav.navigate("notifications") }, modifier = Modifier.testTag("bell")) {
                val n = d?.unread ?: 0
                BadgedBox(badge = { if (n > 0) Badge { Text(if (n > 99) "99+" else n.toString()) } }) {
                    Icon(Icons.Filled.Notifications, stringResource(R.string.notifications))
                }
            }
        },
        bottomBar = {
            NavigationBar {
                TABS.forEach { t ->
                    NavigationBarItem(selected = tab == t.key, onClick = { tab = t.key }, icon = { Icon(t.icon, null) },
                        label = { Text(stringResource(t.label), maxLines = 1) }, modifier = Modifier.testTag("tab_${t.key}"))
                }
            }
        },
    ) { pad ->
        when (tab) {
            "home" -> Loaded(h) { HomeTab(it, nav, pad, onRefresh = { h.refresh() }) }
            "entries" -> EntriesTab(placeId, d?.place?.can?.manage == true, nav, pad)
            "bills" -> BillsTab(placeId, d?.place, nav, pad)
            "book" -> PersonalTab(nav, pad)
            else -> MoreTab(placeId, d?.place, nav, pad)
        }
    }
}

/** Android 13+: ask once so payment reminders and bills can show as phone notifications. */
@Composable
private fun AskNotificationPermission() {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && Graph.prefs.notificationsOn && !Notify.canNotify(ctx)) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun HomeTab(d: Dashboard, nav: Nav, pad: androidx.compose.foundation.layout.PaddingValues, onRefresh: () -> Unit) {
    val p = d.place
    val placeId = p.id
    val s = LocalStatus.current
    ScrollColumn(pad) {
        AddTeaCard(d, onRefresh)

        // My bills: what I owe or get back.
        val owe = d.myBills.sumOf { it.remainingPaisa }
        SectionCard(stringResource(R.string.my_bills)) {
            BalanceText(owe, big = true)
            if (d.advancePaisa > 0) SmallLabel(stringResource(R.string.advance_held, money(d.advancePaisa)), s.good)
            d.myBills.forEach { b -> BillRow(b, showName = false, onClick = { nav.navigate(Routes.bill(b.id)) }) }
            if (d.myBills.isEmpty()) Hint(stringResource(R.string.no_bills_yet))
            if (owe > 0) PrimaryButton(stringResource(R.string.pay_now), icon = Icons.Filled.Payments,
                onClick = { nav.navigate(Routes.pay(placeId, d.myBills.firstOrNull { it.remainingPaisa > 0 }?.id)) })
        }

        // This month so far.
        val m = d.month
        SectionCard(stringResource(R.string.this_month_so_far, fmtMonth(m.month)), onClick = { nav.navigate(Routes.month(placeId, m.month)) }) {
            // Only the bills this person is on (managers see all).
            if (m.sees.tea) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigNumber(stringResource(R.string.tea_rate_per_cup), m.tea.rate?.let { money(Math.round(it * 100)) } ?: "—", Modifier.weight(1f))
                    BigNumber(stringResource(R.string.cups_made), m.tea.cupsTotal.toString(), Modifier.weight(1f))
                }
                InfoRow(stringResource(R.string.tea_spent), money(m.tea.totalPaisa))
            }
            if (m.sees.kitchen) InfoRow(stringResource(R.string.kitchen_spent), money(m.kitchen.sharedPaisa))
            if (m.sees.meals) InfoRow(stringResource(R.string.meals_spent), money(m.kitchen.mealsPaisa))
            d.mySoFar?.let { r ->
                Spacer(Modifier.height(4.dp))
                SmallLabel(stringResource(R.string.my_share_so_far))
                InfoRow(pluralStringResource(R.plurals.my_cups, r.cups, r.cups), money(r.teaPaisa))
                if (r.kitchenSharedPaisa > 0 || r.kitchenDays != null) InfoRow(stringResource(R.string.kitchen_share), money(r.kitchenSharedPaisa))
                if (r.mealsPaisa > 0) InfoRow(pluralStringResource(R.plurals.meals_n, r.mealsCount, r.mealsCount), money(r.mealsPaisa))
                if (r.partyPaisa > 0) InfoRow(stringResource(R.string.parties), money(r.partyPaisa))
                if (r.creditPaisa > 0) InfoRow(stringResource(R.string.i_paid_for_group), "− " + money(r.creditPaisa), valueColor = s.good)
                InfoRow(stringResource(R.string.so_far_total), money(r.totalPaisa), strong = true)
            }
            Hint(stringResource(R.string.estimate_note))
        }

        // Pending bills, visible to everyone.
        if (d.pending.isNotEmpty()) {
            SectionCard(stringResource(R.string.pending_bills_all)) {
                SmallLabel(stringResource(R.string.pending_total, money(d.pending.sumOf { it.remainingPaisa }), d.pending.size))
                d.pending.take(12).forEach { b -> BillRow(b, showName = true, onClick = { nav.navigate(Routes.bill(b.id)) }) }
                if (d.pending.size > 12) Hint(stringResource(R.string.more_in_bills, d.pending.size - 12))
                Hint(stringResource(R.string.only_manager_marks))
            }
        }

        // Today.
        SectionCard(stringResource(R.string.today_title, fmtDate(d.today))) {
            InfoRow(stringResource(R.string.tea_counted_today), d.teaToday.official.toString())
            InfoRow(stringResource(R.string.meals_today), d.mealsToday.toString())
            if (d.onLeaveToday.isNotEmpty()) InfoRow(stringResource(R.string.on_leave_today), d.onLeaveToday.joinToString(", "))
            if (d.month.warnings.isNotEmpty()) d.month.warnings.forEach { Text("• " + warningText(it), color = s.part, style = MaterialTheme.typography.bodySmall) }
        }

        // Manager panel.
        if (p.can.manage) ManagerPanel(d, nav)
        else SectionCard(stringResource(R.string.managed_by)) {
            Text(p.managers.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodyLarge)
            Hint(stringResource(R.string.member_can_do))
            TextButton(onClick = { nav.navigate(Routes.myTea(placeId)) }) { Text(stringResource(R.string.my_tea_record)) }
        }
    }
}

@Composable
fun warningText(code: String): String = when (code) {
    "no_tea_counted" -> stringResource(R.string.warn_no_tea_counted)
    "no_tea_members" -> stringResource(R.string.warn_no_tea_members)
    "no_kitchen_members" -> stringResource(R.string.warn_no_kitchen_members)
    else -> code.replace('_', ' ')
}

/** The one-tap private tea mark. Only the member sees it; it checks the manager's count at the end of the month. */
@Composable
private fun AddTeaCard(d: Dashboard, onRefresh: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var marks by remember(d.teaToday.myMarks) { mutableIntStateOf(d.teaToday.myMarks) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun change(add: Boolean) {
        if (busy) return
        scope.launch {
            busy = true; error = null
            try {
                val r = if (add) Graph.repo.myTeaAdd(d.place.id) else Graph.repo.myTeaUndo(d.place.id)
                marks = r.todayCups
            } catch (e: Exception) { error = plainError(ctx, e) } finally { busy = false }
        }
    }
    if (!d.place.me.onTea) return
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.my_tea_today), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(pluralStringResource(R.plurals.cups_marked, marks, marks), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                SmallLabel(stringResource(R.string.manager_counted, d.teaToday.myOfficial))
            }
            Button(onClick = { change(true) }, enabled = !busy, modifier = Modifier.height(64.dp).testTag("add_tea"), shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondary)
                else {
                    Icon(Icons.Filled.EmojiFoodBeverage, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_tea), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        AnimatedVisibility(marks > 0) {
            TextButton(onClick = { change(false) }, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.undo_last_cup))
            }
        }
        Hint(stringResource(R.string.add_tea_private))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ManagerPanel(d: Dashboard, nav: Nav) {
    val placeId = d.place.id
    val a = d.admin
    SectionCard(stringResource(R.string.manager_panel)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickButton(Icons.Filled.EmojiFoodBeverage, stringResource(R.string.count_tea), "q_tea") { nav.navigate(Routes.tea(placeId)) }
            QuickButton(Icons.Filled.ShoppingBasket, stringResource(R.string.tea_purchase), "q_tea_buy") { nav.navigate(Routes.purchase(placeId, bill = "tea")) }
            QuickButton(Icons.Filled.Add, stringResource(R.string.kitchen_purchase), "q_kitchen_buy") { nav.navigate(Routes.purchase(placeId, bill = "kitchen")) }
            QuickButton(Icons.Filled.Restaurant, stringResource(R.string.add_meal), "q_meal") { nav.navigate(Routes.meal(placeId)) }
            QuickButton(Icons.Filled.Celebration, stringResource(R.string.add_party), "q_party") { nav.navigate(Routes.party(placeId)) }
        }
        if (a != null) {
            Spacer(Modifier.height(6.dp))
            if (a.slipsWaiting > 0) ActionLine(pluralStringResource(R.plurals.slips_waiting, a.slipsWaiting, a.slipsWaiting)) { nav.navigate(Routes.slips(placeId)) }
            if (a.joinRequests > 0) ActionLine(pluralStringResource(R.plurals.join_requests_n, a.joinRequests, a.joinRequests)) { nav.navigate(Routes.requests(placeId)) }
            if (a.openQuestions > 0) ActionLine(pluralStringResource(R.plurals.questions_open, a.openQuestions, a.openQuestions)) { nav.navigate(Routes.questions(placeId)) }
            a.unbilledMonths.forEach { mm -> ActionLine(stringResource(R.string.month_not_billed, fmtMonth(mm))) { nav.navigate(Routes.month(placeId, mm)) } }
            if (d.pending.isNotEmpty()) ActionLine(stringResource(R.string.send_reminders)) { nav.navigate(Routes.remind(placeId)) }
        }
    }
}

@Composable
private fun QuickButton(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    androidx.compose.material3.FilledTonalButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label)
    }
}

@Composable
private fun ActionLine(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(0.dp)) {
        Text("→ $text", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}
