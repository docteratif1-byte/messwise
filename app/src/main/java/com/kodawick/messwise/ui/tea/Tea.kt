package com.kodawick.messwise.ui.tea

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiFoodBeverage
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kodawick.messwise.R
import com.kodawick.messwise.data.Graph
import com.kodawick.messwise.data.model.TeaDay
import com.kodawick.messwise.ui.Nav
import com.kodawick.messwise.ui.Routes
import com.kodawick.messwise.ui.common.AppScreen
import com.kodawick.messwise.ui.common.BigNumber
import com.kodawick.messwise.ui.common.DateField
import com.kodawick.messwise.ui.common.EmptyState
import com.kodawick.messwise.ui.common.Hint
import com.kodawick.messwise.ui.common.InfoRow
import com.kodawick.messwise.ui.common.Loaded
import com.kodawick.messwise.ui.common.MonthSwitcher
import com.kodawick.messwise.ui.common.PrimaryButton
import com.kodawick.messwise.ui.common.ScrollColumn
import com.kodawick.messwise.ui.common.SecondaryButton
import com.kodawick.messwise.ui.common.SectionCard
import com.kodawick.messwise.ui.common.SmallLabel
import com.kodawick.messwise.ui.common.StatusChip
import com.kodawick.messwise.ui.common.Stepper
import com.kodawick.messwise.ui.common.fmtDate
import com.kodawick.messwise.ui.common.money
import com.kodawick.messwise.ui.common.rememberAction
import com.kodawick.messwise.ui.common.rememberLoad
import com.kodawick.messwise.ui.common.thisMonth
import com.kodawick.messwise.ui.common.todayPk
import com.kodawick.messwise.ui.theme.LocalStatus

/** The manager's daily tea count: cups per member, their personal guests, and office guests. */
@Composable
fun TeaCounterScreen(placeId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var day by rememberSaveable { mutableStateOf(todayPk().toString()) }
    val h = rememberLoad("tea", placeId, day) { Graph.repo.teaDay(placeId, day) }
    val cups = remember(h.data) { mutableStateMapOf<String, Int>().apply { h.data?.rows?.forEach { put(it.memberId, it.cups) } } }
    val guests = remember(h.data) { mutableStateMapOf<String, Int>().apply { h.data?.rows?.forEach { put(it.memberId, it.guestCups) } } }
    var office by remember(h.data) { mutableIntStateOf(h.data?.officeCups ?: 0) }
    val action = rememberAction()
    fun save(t: TeaDay) = action.run(success = ctx.getString(R.string.tea_saved)) {
        val rows = t.rows.flatMap { r ->
            listOf(
                mapOf("subject" to "member", "member_id" to r.memberId, "cups" to (cups[r.memberId] ?: 0)),
                mapOf("subject" to "personal_guest", "member_id" to r.memberId, "cups" to (guests[r.memberId] ?: 0)),
            )
        } + listOf(mapOf("subject" to "office_guest", "member_id" to null, "cups" to office))
        h.data = Graph.repo.teaSet(placeId, day, rows)
    }
    AppScreen(stringResource(R.string.count_tea), onBack = onBack) { pad ->
        Loaded(h) { t ->
            val dirty = t.rows.any { (cups[it.memberId] ?: 0) != it.cups || (guests[it.memberId] ?: 0) != it.guestCups } || office != t.officeCups
            ScrollColumn(pad) {
                DateField(day, { day = it }, stringResource(R.string.day))
                if (!t.canEdit) Hint(stringResource(R.string.month_locked_tea))
                SectionCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BigNumber(stringResource(R.string.cups_today), (t.rows.sumOf { (cups[it.memberId] ?: 0) + (guests[it.memberId] ?: 0) } + office).toString(), Modifier.weight(1f))
                        BigNumber(stringResource(R.string.cups_this_month), t.month.cupsTotal.toString(), Modifier.weight(1f))
                    }
                    InfoRow(stringResource(R.string.tea_rate_so_far), t.month.rate?.let { money(Math.round(it * 100)) } ?: "—")
                }
                if (t.canEdit) SecondaryButton(stringResource(R.string.one_round_all), icon = Icons.Filled.EmojiFoodBeverage, onClick = {
                    t.rows.filter { it.onTea && !it.onLeave }.forEach { cups[it.memberId] = (cups[it.memberId] ?: 0) + 1 }
                }, modifier = Modifier.testTag("round_all"))
                SectionCard(stringResource(R.string.members)) {
                    if (t.rows.isEmpty()) Hint(stringResource(R.string.no_tea_members))
                    t.rows.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider()
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    if (r.onLeave) StatusChip("on_leave")
                                }
                                Stepper(cups[r.memberId] ?: 0, { cups[r.memberId] = it }, enabled = t.canEdit)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SmallLabel(stringResource(R.string.their_guests), modifier = Modifier.weight(1f))
                                Stepper(guests[r.memberId] ?: 0, { guests[r.memberId] = it }, enabled = t.canEdit)
                            }
                        }
                    }
                }
                SectionCard(stringResource(R.string.office_guests)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.office_guests_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Stepper(office, { office = it }, enabled = t.canEdit)
                    }
                }
                Hint(stringResource(R.string.tea_count_hint))
                if (t.canEdit) PrimaryButton(stringResource(R.string.save_tea_count), enabled = dirty, busy = action.busy, onClick = { save(t) },
                    modifier = Modifier.testTag("save_tea"))
            }
        }
    }
}

/** A member's private tea marks next to the manager's count, day by day. */
@Composable
fun MyTeaScreen(placeId: String, nav: Nav, onBack: () -> Unit) {
    var month by rememberSaveable { mutableStateOf(thisMonth()) }
    val h = rememberLoad("mytea", placeId, month) { Graph.repo.myTea(placeId, month) }
    val s = LocalStatus.current
    AppScreen(stringResource(R.string.my_tea_record), onBack = onBack) { pad ->
        Loaded(h) { t ->
            ScrollColumn(pad) {
                MonthSwitcher(month) { month = it }
                SectionCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BigNumber(stringResource(R.string.my_marks), t.mineTotal.toString(), Modifier.weight(1f))
                        BigNumber(stringResource(R.string.manager_count), t.officialTotal.toString(), Modifier.weight(1f),
                            color = if (t.officialTotal > t.mineTotal) s.owe else MaterialTheme.colorScheme.onSurface)
                    }
                    t.tea.rate?.let { r ->
                        InfoRow(stringResource(R.string.tea_rate_so_far), money(Math.round(r * 100)))
                        InfoRow(stringResource(R.string.my_tea_bill_estimate), money(Math.round(r * 100) * t.officialTotal), strong = true)
                    }
                    val diff = t.officialTotal - t.mineTotal
                    if (diff != 0) Text(
                        if (diff > 0) pluralStringResource(R.plurals.manager_more, diff, diff) else pluralStringResource(R.plurals.manager_less, -diff, -diff),
                        color = if (diff > 0) s.owe else s.info, style = MaterialTheme.typography.bodyMedium)
                    Hint(stringResource(R.string.my_tea_private_hint))
                }
                if (t.days.isEmpty()) EmptyState(Icons.Filled.EmojiFoodBeverage, stringResource(R.string.no_tea_this_month))
                else SectionCard(stringResource(R.string.day_by_day)) {
                    Row(Modifier.fillMaxWidth()) {
                        SmallLabel(stringResource(R.string.day), modifier = Modifier.weight(1f))
                        SmallLabel(stringResource(R.string.mine), modifier = Modifier.width(64.dp))
                        SmallLabel(stringResource(R.string.manager_short), modifier = Modifier.width(72.dp))
                    }
                    t.days.forEach { d ->
                        val off = d.mine != d.official
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(fmtDate(d.day), modifier = Modifier.weight(1f))
                            Text(d.mine.toString(), modifier = Modifier.width(64.dp))
                            Text(d.official.toString(), modifier = Modifier.width(72.dp), fontWeight = if (off) FontWeight.Bold else FontWeight.Normal,
                                color = if (off) s.owe else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                if (t.days.any { it.mine != it.official }) SecondaryButton(stringResource(R.string.ask_manager_tea), icon = Icons.Filled.QuestionAnswer,
                    onClick = { nav.navigate(Routes.ask(placeId, "tea", month = month)) })
                Spacer(Modifier.width(1.dp))
            }
        }
    }
}
