package com.kodawick.messwise.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kodawick.messwise.MainActivity
import com.kodawick.messwise.data.Graph
import com.kodawick.messwise.ui.auth.LoginScreen
import com.kodawick.messwise.ui.auth.ProfileSetupScreen
import com.kodawick.messwise.ui.bills.BillScreen
import com.kodawick.messwise.ui.bills.MonthScreen
import com.kodawick.messwise.ui.bills.PayScreen
import com.kodawick.messwise.ui.bills.RemindScreen
import com.kodawick.messwise.ui.bills.SlipsScreen
import com.kodawick.messwise.ui.common.Feedback
import com.kodawick.messwise.ui.common.LocalFeedback
import com.kodawick.messwise.ui.entries.EntryScreen
import com.kodawick.messwise.ui.entries.MealFormScreen
import com.kodawick.messwise.ui.entries.PartyFormScreen
import com.kodawick.messwise.ui.entries.PurchaseFormScreen
import com.kodawick.messwise.ui.home.PlaceScreen
import com.kodawick.messwise.ui.lock.LockScreen
import com.kodawick.messwise.ui.members.JoinRequestsScreen
import com.kodawick.messwise.ui.members.MemberScreen
import com.kodawick.messwise.ui.members.MembersScreen
import com.kodawick.messwise.ui.more.AboutScreen
import com.kodawick.messwise.ui.more.ActivityScreen
import com.kodawick.messwise.ui.more.AskScreen
import com.kodawick.messwise.ui.more.NoticeScreen
import com.kodawick.messwise.ui.more.NotificationsScreen
import com.kodawick.messwise.ui.more.PlaceSettingsScreen
import com.kodawick.messwise.ui.more.QuestionsScreen
import com.kodawick.messwise.ui.more.SettingsScreen
import com.kodawick.messwise.ui.personal.PersonalFormScreen
import com.kodawick.messwise.ui.places.CreatePlaceScreen
import com.kodawick.messwise.ui.places.InviteScreen
import com.kodawick.messwise.ui.places.JoinScreen
import com.kodawick.messwise.ui.places.PlacesScreen
import com.kodawick.messwise.ui.reports.ReportsScreen
import com.kodawick.messwise.ui.tea.MyTeaScreen
import com.kodawick.messwise.ui.tea.TeaCounterScreen
import com.kodawick.messwise.ui.theme.MesswiseTheme

typealias Nav = NavHostController

@Composable
fun AppRoot(activity: MainActivity) {
    var themePref by remember { mutableStateOf(Graph.prefs.theme) }
    var locked by rememberSaveable { mutableStateOf(Graph.prefs.appLock && Graph.session.signedIn) }
    // Lock again when the app comes back after more than a minute in the background.
    DisposableEffect(Unit) {
        var leftAt = 0L
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) leftAt = System.currentTimeMillis()
            if (e == Lifecycle.Event.ON_START && leftAt > 0 && Graph.prefs.appLock && Graph.session.signedIn &&
                System.currentTimeMillis() - leftAt > 60_000) locked = true
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(obs)
        onDispose { ProcessLifecycleOwner.get().lifecycle.removeObserver(obs) }
    }
    MesswiseTheme(themePref) {
        val snack = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        CompositionLocalProvider(LocalFeedback provides remember { Feedback(snack, scope) }) {
            Box(Modifier.fillMaxSize()) {
                if (locked) LockScreen(activity, onUnlocked = { locked = false })
                else AppNav(activity, onThemeChanged = { themePref = it })
            }
        }
    }
}

object Routes {
    const val LOGIN = "login"
    const val SETUP = "setup"
    const val START = "start"
    const val PLACES = "places"
    fun place(id: String) = "place/$id"
    fun tea(place: String) = "tea/$place"
    fun myTea(place: String) = "mytea/$place"
    fun purchase(place: String, id: String? = null, bill: String = "tea") = "purchase/$place?id=${id ?: ""}&bill=$bill"
    fun meal(place: String, id: String? = null) = "meal/$place?id=${id ?: ""}"
    fun party(place: String, id: String? = null) = "party/$place?id=${id ?: ""}"
    fun entry(type: String, id: String) = "entry/$type/$id"
    fun members(place: String) = "members/$place"
    fun member(id: String) = "member/$id"
    fun requests(place: String) = "requests/$place"
    fun month(place: String, month: String) = "month/$place/$month"
    fun bill(id: String) = "bill/$id"
    fun pay(place: String, bill: String?) = "pay/$place?bill=${bill ?: ""}"
    fun slips(place: String) = "slips/$place"
    fun remind(place: String) = "remind/$place"
    fun notice(place: String) = "notice/$place"
    fun reports(place: String) = "reports/$place"
    fun questions(place: String) = "questions/$place"
    fun ask(place: String, entity: String, id: String? = null, month: String? = null) = "ask/$place?entity=$entity&id=${id ?: ""}&month=${month ?: ""}"
    fun activity(place: String) = "activity/$place"
    fun placeSettings(place: String) = "place_settings/$place"
    fun invite(place: String) = "invite/$place"
    fun personal(id: String? = null, month: String? = null) = "personal?id=${id ?: ""}&month=${month ?: ""}"
}

@Composable
fun AppNav(activity: MainActivity, onThemeChanged: (String) -> Unit) {
    val nav = rememberNavController()
    val tokens by Graph.session.tokens.collectAsState()
    val start = remember { if (!Graph.session.signedIn) Routes.LOGIN else Routes.START }
    LaunchedEffect(tokens == null) {
        if (tokens == null && nav.currentDestination?.route !in setOf(Routes.LOGIN, null)) {
            nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
        }
    }
    LaunchedEffect(activity.pendingLink, tokens) {
        val link = activity.pendingLink ?: return@LaunchedEffect
        if (tokens != null && nav.currentDestination?.route != null) {
            activity.pendingLink = null
            nav.navigate(link)
        }
    }
    val back: () -> Unit = { if (!nav.popBackStack()) activity.finish() }
    val opt = { name: String -> navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null } }
    NavHost(nav, startDestination = start) {
        composable(Routes.LOGIN) {
            LoginScreen(activity, onSignedIn = { needsName ->
                nav.navigate(if (needsName) Routes.SETUP else Routes.START) { popUpTo(0) { inclusive = true } }
            })
        }
        composable(Routes.SETUP) { ProfileSetupScreen(onDone = { nav.navigate(Routes.START) { popUpTo(0) { inclusive = true } } }) }
        composable(Routes.START) { StartScreen(nav) }
        composable(Routes.PLACES) { PlacesScreen(nav) }
        composable("create") { CreatePlaceScreen(onBack = back, onCreated = { id -> openPlace(nav, id) }) }
        composable("join?code={code}", arguments = listOf(opt("code"))) { e ->
            JoinScreen(initialCode = e.opt("code"), onBack = back, onMember = { id -> openPlace(nav, id) })
        }
        composable("place/{id}") { e -> PlaceScreen(e.arg("id"), nav, activity, onThemeChanged) }
        composable("tea/{place}") { e -> TeaCounterScreen(e.arg("place"), back) }
        composable("mytea/{place}") { e -> MyTeaScreen(e.arg("place"), nav, back) }
        composable("purchase/{place}?id={id}&bill={bill}", arguments = listOf(opt("id"), opt("bill"))) { e ->
            PurchaseFormScreen(e.arg("place"), e.opt("id"), e.opt("bill") ?: "tea", back)
        }
        composable("meal/{place}?id={id}", arguments = listOf(opt("id"))) { e -> MealFormScreen(e.arg("place"), e.opt("id"), back) }
        composable("party/{place}?id={id}", arguments = listOf(opt("id"))) { e -> PartyFormScreen(e.arg("place"), e.opt("id"), back) }
        composable("entry/{type}/{id}") { e -> EntryScreen(e.arg("type"), e.arg("id"), nav, back) }
        composable("members/{place}") { e -> MembersScreen(e.arg("place"), nav, back) }
        composable("member/{id}") { e -> MemberScreen(e.arg("id"), nav, back) }
        composable("requests/{place}") { e -> JoinRequestsScreen(e.arg("place"), back) }
        composable("month/{place}/{month}") { e -> MonthScreen(e.arg("place"), e.arg("month"), nav, back) }
        composable("bill/{id}") { e -> BillScreen(e.arg("id"), nav, back) }
        composable("pay/{place}?bill={bill}", arguments = listOf(opt("bill"))) { e -> PayScreen(e.arg("place"), e.opt("bill"), back) }
        composable("slips/{place}") { e -> SlipsScreen(e.arg("place"), nav, back) }
        composable("remind/{place}") { e -> RemindScreen(e.arg("place"), back) }
        composable("notice/{place}") { e -> NoticeScreen(e.arg("place"), back) }
        composable("reports/{place}") { e -> ReportsScreen(e.arg("place"), back) }
        composable("questions/{place}") { e -> QuestionsScreen(e.arg("place"), back) }
        composable("ask/{place}?entity={entity}&id={id}&month={month}", arguments = listOf(opt("entity"), opt("id"), opt("month"))) { e ->
            AskScreen(e.arg("place"), e.opt("entity") ?: "other", e.opt("id"), e.opt("month"), back)
        }
        composable("activity/{place}") { e -> ActivityScreen(e.arg("place"), back) }
        composable("place_settings/{place}") { e -> PlaceSettingsScreen(e.arg("place"), nav, back) }
        composable("invite/{place}") { e -> InviteScreen(e.arg("place"), back) }
        composable("personal?id={id}&month={month}", arguments = listOf(opt("id"), opt("month"))) { e -> PersonalFormScreen(e.opt("id"), e.opt("month"), back) }
        composable("notifications") { NotificationsScreen(nav, back) }
        composable("settings") { SettingsScreen(activity, nav, back, onThemeChanged) }
        composable("about") { AboutScreen(back) }
    }
}

/** Opens the office or hostel last used, or the list when there is none. */
@Composable
private fun StartScreen(nav: Nav) {
    LaunchedEffect(Unit) {
        val me = try { Graph.repo.me() } catch (_: Exception) { Graph.repo.meCached() }
        val last = Graph.prefs.lastPlaceId
        val target = me?.places?.firstOrNull { it.id == last }?.id ?: me?.places?.singleOrNull()?.id
        if (target != null) openPlace(nav, target) else nav.navigate(Routes.PLACES) { popUpTo(0) { inclusive = true } }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

fun openPlace(nav: Nav, id: String) {
    Graph.prefs.lastPlaceId = id
    nav.navigate(Routes.place(id)) { popUpTo(0) { inclusive = true } }
}

private fun NavBackStackEntry.arg(name: String) = arguments?.getString(name) ?: ""
private fun NavBackStackEntry.opt(name: String) = arguments?.getString(name)?.takeIf { it.isNotBlank() }
