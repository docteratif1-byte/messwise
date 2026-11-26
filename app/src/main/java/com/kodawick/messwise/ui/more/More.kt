package com.kodawick.messwise.ui.more

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.EmojiFoodBeverage
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import com.kodawick.messwise.BuildConfig
import com.kodawick.messwise.R
import com.kodawick.messwise.data.Graph
import com.kodawick.messwise.data.model.Place
import com.kodawick.messwise.data.model.Question
import com.kodawick.messwise.ui.Nav
import com.kodawick.messwise.ui.Routes
import com.kodawick.messwise.ui.auth.BrandMark
import com.kodawick.messwise.ui.auth.KodaWickCredit
import com.kodawick.messwise.ui.auth.SUPPORT_EMAIL
import com.kodawick.messwise.ui.auth.emailSupport
import com.kodawick.messwise.ui.common.AppScreen
import com.kodawick.messwise.ui.common.ChoiceRow
import com.kodawick.messwise.ui.common.EmptyState
import com.kodawick.messwise.ui.common.Hint
import com.kodawick.messwise.ui.common.InfoRow
import com.kodawick.messwise.ui.common.LinkRow
import com.kodawick.messwise.ui.common.Loaded
import com.kodawick.messwise.ui.common.PhotoUpload
import com.kodawick.messwise.ui.common.PrimaryButton
import com.kodawick.messwise.ui.common.ScrollColumn
import com.kodawick.messwise.ui.common.SectionCard
import com.kodawick.messwise.ui.common.SmallLabel
import com.kodawick.messwise.ui.common.StatusChip
import com.kodawick.messwise.ui.common.TextInput
import com.kodawick.messwise.ui.common.fmtDateTime
import com.kodawick.messwise.ui.common.fmtMonth
import com.kodawick.messwise.ui.common.rememberAction
import com.kodawick.messwise.ui.common.rememberLoad
import com.kodawick.messwise.ui.entries.SwitchRow
import com.kodawick.messwise.ui.openPlace

const val PRIVACY_URL = "https://messwise-r9qh.onrender.com/privacy.html"
const val DELETE_URL = "https://messwise-r9qh.onrender.com/delete-account.html"

fun openUrl(ctx: android.content.Context, url: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
}

@Composable
fun MoreTab(placeId: String, place: Place?, nav: Nav, pad: PaddingValues) {
    val manage = place?.can?.manage == true
    ScrollColumn(pad) {
        SectionCard {
            MoreLink(Icons.Filled.Groups, stringResource(R.string.members), "more_members") { nav.navigate(Routes.members(placeId)) }
            MoreLink(Icons.Filled.EmojiFoodBeverage, stringResource(R.string.my_tea_record)) { nav.navigate(Routes.myTea(placeId)) }
            MoreLink(Icons.Filled.Insights, stringResource(R.string.reports), "more_reports") { nav.navigate(Routes.reports(placeId)) }
            MoreLink(Icons.Filled.QuestionAnswer, stringResource(if (manage) R.string.questions else R.string.my_questions)) { nav.navigate(Routes.questions(placeId)) }
            MoreLink(Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.ask_manager)) { nav.navigate(Routes.ask(placeId, "other")) }
            MoreLink(Icons.Filled.History, stringResource(R.string.activity)) { nav.navigate(Routes.activity(placeId)) }
        }
        if (manage) SectionCard(stringResource(R.string.manager_panel)) {
            MoreLink(Icons.Filled.GroupAdd, stringResource(R.string.invite_members)) { nav.navigate(Routes.invite(placeId)) }
            MoreLink(Icons.Filled.GroupAdd, stringResource(R.string.join_requests)) { nav.navigate(Routes.requests(placeId)) }
            MoreLink(Icons.Filled.Campaign, stringResource(R.string.send_notice)) { nav.navigate(Routes.notice(placeId)) }
            MoreLink(Icons.Filled.Tune, stringResource(R.string.place_settings)) { nav.navigate(Routes.placeSettings(placeId)) }
        }
        SectionCard {
            MoreLink(Icons.Filled.SwapHoriz, stringResource(R.string.switch_place)) { nav.navigate("places") }
            MoreLink(Icons.Filled.Notifications, stringResource(R.string.notifications)) { nav.navigate("notifications") }
            MoreLink(Icons.Filled.Settings, stringResource(R.string.settings), "more_settings") { nav.navigate("settings") }
            MoreLink(Icons.Filled.Info, stringResource(R.string.about)) { nav.navigate("about") }
        }
        KodaWickCredit()
    }
}

@Composable
private fun MoreLink(icon: ImageVector, title: String, tag: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp).let { if (tag != null) it.testTag(tag) else it },
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text("   $title", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------- notices, notifications, activity

@Composable
fun NoticeScreen(placeId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val action = rememberAction()
    AppScreen(stringResource(R.string.send_notice), onBack = onBack) { pad ->
        ScrollColumn(pad) {
            Hint(stringResource(R.string.notice_hint))
            TextInput(text, { text = it.take(500) }, stringResource(R.string.notice_text), singleLine = false, minLines = 4, modifier = Modifier.testTag("notice_text"))
            PrimaryButton(stringResource(R.string.send_to_all), enabled = text.trim().length >= 3, busy = action.busy, onClick = {
                action.run(success = ctx.getString(R.string.notice_sent), after = { onBack() }) { Graph.repo.notice(placeId, text.trim()) }
            })
        }
    }
}

@Composable
fun NotificationsScreen(nav: Nav, onBack: () -> Unit) {
    val h = rememberLoad("notifications", cached = { Graph.repo.notificationsCached() }) { Graph.repo.notifications() }
    LaunchedEffect(h.data?.firstOrNull()?.id) {
        if (h.data?.any { !it.read } == true) try { Graph.repo.notificationsRead(null) } catch (_: Exception) {}
    }
    AppScreen(stringResource(R.string.notifications), onBack = onBack) { pad ->
        Loaded(h) { list ->
            ScrollColumn(pad) {
                if (list.isEmpty()) EmptyState(Icons.Filled.Notifications, stringResource(R.string.no_notifications))
                list.forEach { n ->
                    SectionCard(onClick = n.placeId?.let { pid -> { openPlace(nav, pid) } }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(n.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (n.read) FontWeight.Normal else FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (!n.read) StatusChip("open")
                        }
                        if (n.body.isNotBlank()) Text(n.body, style = MaterialTheme.typography.bodyMedium)
                        SmallLabel(listOfNotNull(n.placeName, fmtDateTime(n.createdAt)).joinToString(" · "))
                    }
                }
            }
        }
    }
}

@Composable
fun ActivityScreen(placeId: String, onBack: () -> Unit) {
    val h = rememberLoad("activity", placeId) { Graph.repo.activity(placeId) }
    AppScreen(stringResource(R.string.activity), onBack = onBack) { pad ->
        Loaded(h) { list ->
            ScrollColumn(pad) {
                Hint(stringResource(R.string.activity_hint))
                if (list.isEmpty()) EmptyState(Icons.Filled.History, stringResource(R.string.no_activity))
                else SectionCard {
                    list.forEachIndexed { i, a ->
                        if (i > 0) HorizontalDivider()
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(a.summary, style = MaterialTheme.typography.bodyMedium)
                            SmallLabel(a.actorName + " · " + fmtDateTime(a.at))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- questions

@Composable
fun QuestionsScreen(placeId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val place = rememberLoad("place", placeId, cached = { Graph.repo.placeCached(placeId) }) { Graph.repo.place(placeId) }
    val h = rememberLoad("questions", placeId) { Graph.repo.questions(placeId) }
    var answering by remember { mutableStateOf<Question?>(null) }
    val action = rememberAction()
    val manage = place.data?.can?.manage == true
    AppScreen(stringResource(if (manage) R.string.questions else R.string.my_questions), onBack = onBack) { pad ->
        Loaded(h) { list ->
            ScrollColumn(pad) {
                if (list.isEmpty()) EmptyState(Icons.Filled.QuestionAnswer, stringResource(R.string.no_questions))
                list.forEach { q ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(q.askedBy, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            StatusChip(if (q.status == "open") "open_question" else "answered")
                        }
                        SmallLabel(listOfNotNull(subjectLabel(q.entity), q.month?.let { fmtMonth(it) }, fmtDateTime(q.createdAt)).joinToString(" · "))
                        Text(q.text, style = MaterialTheme.typography.bodyLarge)
                        q.tally?.let { t -> Hint(stringResource(R.string.tally_line, t.mineTotal, t.officialTotal)) }
                        q.answer?.let { Text("↳ $it", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
                        if (manage && q.status == "open") TextButton(onClick = { answering = q }) { Text(stringResource(R.string.answer)) }
                    }
                }
            }
        }
    }
    answering?.let { q ->
        var text by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest = { answering = null }, title = { Text(stringResource(R.string.answer)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(q.text); TextInput(text, { text = it.take(500) }, stringResource(R.string.your_answer), singleLine = false, minLines = 3) } },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = {
                answering = null
                action.run(success = ctx.getString(R.string.answer_sent)) { h.data = Graph.repo.answer(q.id, text.trim()) }
            }) { Text(stringResource(R.string.send)) } },
            dismissButton = { TextButton(onClick = { answering = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun subjectLabel(entity: String): String = when (entity) {
    "purchase" -> stringResource(R.string.purchase)
    "meal" -> stringResource(R.string.meal_bill)
    "party" -> stringResource(R.string.party)
    "tea" -> stringResource(R.string.tea)
    "bill" -> stringResource(R.string.bill)
    else -> stringResource(R.string.general)
}

/** A member asks the manager about an entry, a bill or their tea count, optionally sharing their private tea tally. */
@Composable
fun AskScreen(placeId: String, entity: String, entityId: String?, month: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var share by rememberSaveable { mutableStateOf(entity == "tea") }
    val action = rememberAction()
    AppScreen(stringResource(R.string.ask_manager), onBack = onBack) { pad ->
        ScrollColumn(pad) {
            SmallLabel(subjectLabel(entity) + (month?.let { " · " + fmtMonth(it) } ?: ""))
            TextInput(text, { text = it.take(500) }, stringResource(R.string.your_question), singleLine = false, minLines = 4, modifier = Modifier.testTag("ask_text"))
            if (entity == "tea") SwitchRow(stringResource(R.string.share_my_tally), stringResource(R.string.share_my_tally_hint), share) { share = it }
            PrimaryButton(stringResource(R.string.send), enabled = text.trim().length >= 3, busy = action.busy, onClick = {
                action.run(success = ctx.getString(R.string.question_sent), after = { onBack() }) {
                    Graph.repo.ask(placeId, entity, entityId, month, text.trim(), share)
                }
            })
            Hint(stringResource(R.string.ask_hint))
        }
    }
}

// ---------------------------------------------------------------- settings

/** Office or hostel details. Managers can change most; owner-only items are checked on the server. */
@Composable
fun PlaceSettingsScreen(placeId: String, nav: Nav, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val h = rememberLoad("place", placeId, cached = { Graph.repo.placeCached(placeId) }) { Graph.repo.place(placeId) }
    val action = rememberAction()
    AppScreen(stringResource(R.string.place_settings), onBack = onBack) { pad ->
        Loaded(h) { p ->
            var name by rememberSaveable { mutableStateOf(p.name) }
            var address by rememberSaveable { mutableStateOf(p.address) }
            var kind by rememberSaveable { mutableStateOf(p.kind) }
            var round by rememberSaveable { mutableStateOf(p.roundToRupee) }
            var split by rememberSaveable { mutableStateOf(p.partyOfficeSplit) }
            var logo by rememberSaveable { mutableStateOf(p.logoPath) }
            ScrollColumn(pad) {
                TextInput(name, { name = it.take(80) }, stringResource(R.string.place_name))
                ChoiceRow(listOf("hostel" to stringResource(R.string.kind_hostel), "office" to stringResource(R.string.kind_office),
                    "flat" to stringResource(R.string.kind_flat), "other" to stringResource(R.string.kind_other)), kind, { kind = it })
                TextInput(address, { address = it.take(160) }, stringResource(R.string.address_optional))
                PhotoUpload(placeId, "logo", logo, { logo = it }, stringResource(R.string.logo_optional))
                SwitchRow(stringResource(R.string.round_rupee), stringResource(R.string.round_rupee_hint), round) { round = it }
                SmallLabel(stringResource(R.string.default_office_guests_party))
                ChoiceRow(listOf("all" to stringResource(R.string.split_everyone), "attendees" to stringResource(R.string.split_attendees)), split, { split = it })
                PrimaryButton(stringResource(R.string.save), enabled = name.trim().length >= 2, busy = action.busy, onClick = {
                    action.run(success = ctx.getString(R.string.saved)) {
                        h.data = Graph.repo.placeUpdate(placeId, mapOf("name" to name.trim(), "address" to address.trim(), "kind" to kind,
                            "round_to_rupee" to round, "party_office_split" to split, "logo_path" to logo))
                    }
                })
                SectionCard(stringResource(R.string.managers)) {
                    p.managers.forEach { m -> InfoRow(m.name, com.kodawick.messwise.ui.common.roleLabel(m.role, m.isMainManager)) }
                    Hint(stringResource(R.string.change_roles_hint))
                    TextButton(onClick = { nav.navigate(Routes.members(placeId)) }) { Text(stringResource(R.string.members)) }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(activity: com.kodawick.messwise.MainActivity, nav: Nav, onBack: () -> Unit, onThemeChanged: (String) -> Unit) {
    val ctx = LocalContext.current
    val me = rememberLoad("me", cached = { Graph.repo.meCached() }) { Graph.repo.me() }
    var theme by remember { mutableStateOf(Graph.prefs.theme) }
    var lang by remember { mutableStateOf(Graph.prefs.language) }
    var lock by remember { mutableStateOf(Graph.prefs.appLock) }
    var notify by remember { mutableStateOf(Graph.prefs.notificationsOn) }
    var editName by remember { mutableStateOf(false) }
    var askOut by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    val action = rememberAction()
    AppScreen(stringResource(R.string.settings), onBack = onBack) { pad ->
        ScrollColumn(pad) {
            SectionCard(stringResource(R.string.account)) {
                me.data?.profile?.let { p ->
                    InfoRow(stringResource(R.string.name), p.fullName)
                    p.email?.let { InfoRow(stringResource(R.string.email), it) }
                }
                TextButton(onClick = { editName = true }) { Text(stringResource(R.string.change_name)) }
            }
            SectionCard(stringResource(R.string.language)) {
                ChoiceRow(listOf("" to stringResource(R.string.phone_language), "en" to "English", "ur" to "اردو"), lang, {
                    lang = it; Graph.prefs.language = it
                    action.run { Graph.repo.profileUpdate(null, it.ifEmpty { "en" }) }
                })
            }
            SectionCard(stringResource(R.string.theme)) {
                ChoiceRow(listOf("system" to stringResource(R.string.theme_system), "light" to stringResource(R.string.theme_light), "dark" to stringResource(R.string.theme_dark)),
                    theme, { theme = it; Graph.prefs.theme = it; onThemeChanged(it) })
            }
            SectionCard(stringResource(R.string.privacy_security)) {
                SwitchRow(stringResource(R.string.app_lock), stringResource(R.string.app_lock_hint), lock) { on ->
                    if (on && !com.kodawick.messwise.ui.lock.AppLock.available(activity)) return@SwitchRow
                    if (on) com.kodawick.messwise.ui.lock.AppLock.prompt(activity, ctx.getString(R.string.unlock_app), onOk = { lock = true; Graph.prefs.appLock = true })
                    else { lock = false; Graph.prefs.appLock = false }
                }
                SwitchRow(stringResource(R.string.phone_notifications), stringResource(R.string.phone_notifications_hint), notify) { on ->
                    notify = on; Graph.prefs.notificationsOn = on
                    if (on) com.kodawick.messwise.notify.Notify.schedule(ctx) else com.kodawick.messwise.notify.Notify.cancel(ctx)
                }
                LinkRow(stringResource(R.string.privacy_policy), onClick = { openUrl(ctx, PRIVACY_URL) })
            }
            SectionCard {
                LinkRow(stringResource(R.string.help_support), SUPPORT_EMAIL, onClick = { emailSupport(ctx, "MessWise help") })
                LinkRow(stringResource(R.string.sign_out), onClick = { askOut = true })
                LinkRow(stringResource(R.string.delete_account), stringResource(R.string.delete_account_sub), onClick = { askDelete = true })
            }
            KodaWickCredit()
        }
    }
    if (editName) {
        var name by rememberSaveable { mutableStateOf(me.data?.profile?.fullName ?: "") }
        AlertDialog(onDismissRequest = { editName = false }, title = { Text(stringResource(R.string.change_name)) },
            text = { TextInput(name, { name = it.take(60) }, stringResource(R.string.full_name)) },
            confirmButton = { TextButton(enabled = name.trim().length >= 2, onClick = {
                editName = false; action.run(success = ctx.getString(R.string.saved)) { Graph.repo.profileUpdate(name.trim(), null); me.refresh() }
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editName = false }) { Text(stringResource(R.string.cancel)) } })
    }
    if (askOut) com.kodawick.messwise.ui.common.ConfirmDialog(stringResource(R.string.sign_out), stringResource(R.string.sign_out_body), stringResource(R.string.sign_out),
        onConfirm = { action.run { com.kodawick.messwise.notify.Notify.cancel(ctx); Graph.api.signOut() } }, onDismiss = { askOut = false })
    if (askDelete) {
        var typed by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest = { askDelete = false }, title = { Text(stringResource(R.string.delete_account)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.delete_account_body))
                TextInput(typed, { typed = it }, stringResource(R.string.type_delete))
            } },
            confirmButton = { TextButton(enabled = typed.trim().equals("DELETE", ignoreCase = true), onClick = {
                askDelete = false
                action.run { Graph.repo.accountDelete(); com.kodawick.messwise.notify.Notify.cancel(ctx); Graph.api.signOut() }
            }) { Text(stringResource(R.string.delete_account), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    AppScreen(stringResource(R.string.about), onBack = onBack) { pad ->
        ScrollColumn(pad) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                BrandMark(84)
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                SmallLabel(stringResource(R.string.version_x, BuildConfig.VERSION_NAME))
            }
            Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyLarge)
            SectionCard(stringResource(R.string.how_bills_work)) {
                Text(stringResource(R.string.how_tea_works), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.how_kitchen_works), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.how_guests_work), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.how_paid_works), style = MaterialTheme.typography.bodyMedium)
            }
            SectionCard {
                LinkRow(stringResource(R.string.privacy_policy), onClick = { openUrl(ctx, PRIVACY_URL) })
                LinkRow(stringResource(R.string.help_support), SUPPORT_EMAIL, leading = { Icon(Icons.Filled.Storefront, null) }, onClick = { emailSupport(ctx, "MessWise") })
                LinkRow(stringResource(R.string.open_source_licences), stringResource(R.string.font_licence), onClick = {})
            }
            KodaWickCredit()
        }
    }
}
