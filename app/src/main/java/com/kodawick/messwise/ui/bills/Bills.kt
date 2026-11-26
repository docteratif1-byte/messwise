package com.kodawick.messwise.ui.bills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.kodawick.messwise.R
import com.kodawick.messwise.data.Graph
import com.kodawick.messwise.data.model.Bill
import com.kodawick.messwise.data.model.Place
import com.kodawick.messwise.ui.Nav
import com.kodawick.messwise.ui.Routes
import com.kodawick.messwise.ui.common.ChoiceRow
import com.kodawick.messwise.ui.common.EmptyState
import com.kodawick.messwise.ui.common.Hint
import com.kodawick.messwise.ui.common.Loaded
import com.kodawick.messwise.ui.common.MonthSwitcher
import com.kodawick.messwise.ui.common.PrimaryButton
import com.kodawick.messwise.ui.common.ScrollColumn
import com.kodawick.messwise.ui.common.SecondaryButton
import com.kodawick.messwise.ui.common.SectionCard
import com.kodawick.messwise.ui.common.SmallLabel
import com.kodawick.messwise.ui.common.StatusChip
import com.kodawick.messwise.ui.common.billTitle
import com.kodawick.messwise.ui.common.money
import com.kodawick.messwise.ui.common.rememberLoad
import com.kodawick.messwise.ui.common.thisMonth
import com.kodawick.messwise.ui.theme.LocalStatus

/** One bill in a list: whose, what for, how much is left, and its state in words. */
@Composable
fun BillRow(b: Bill, showName: Boolean, onClick: () -> Unit) {
    val s = LocalStatus.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (showName) b.memberName else billTitle(b), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            SmallLabel(if (showName) billTitle(b) + " · " + money(b.totalPaisa) else stringResource(R.string.bill_total_x, money(b.totalPaisa)))
        }
        Column(horizontalAlignment = Alignment.End) {
            val left = b.remainingPaisa
            if (left != 0L) Text(money(kotlin.math.abs(left)), fontWeight = FontWeight.Bold, color = if (left > 0) s.owe else s.good)
            StatusChip(b.status)
        }
    }
}

/** Bills of a month for everyone (open to all members), the month sheet, and the manager's tools. */
@Composable
fun BillsTab(placeId: String, place: Place?, nav: Nav, pad: PaddingValues) {
    var month by rememberSaveable { mutableStateOf(thisMonth()) }
    var filter by rememberSaveable { mutableStateOf("all") }
    val h = rememberLoad("bills", placeId, month) { Graph.repo.bills(placeId, month, unpaid = false) }
    val manage = place?.can?.handlesMoney == true
    Loaded(h, Modifier.padding(pad)) { all ->
        val me = place?.me?.id
        val bills = all.filter {
            when (filter) {
                "mine" -> it.memberId == me
                "unpaid" -> it.remainingPaisa != 0L
                else -> true
            }
        }.sortedWith(compareByDescending<Bill> { it.memberId == me }.thenBy { it.kind }.thenBy { it.memberName })
        ScrollColumn(PaddingValues(0.dp)) {
            MonthSwitcher(month) { month = it }
            PrimaryButton(stringResource(R.string.open_month_sheet), icon = Icons.Filled.CalendarMonth, onClick = { nav.navigate(Routes.month(placeId, month)) },
                modifier = Modifier.testTag("open_month"))
            if (manage) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.slips), icon = Icons.Filled.Payments, modifier = Modifier.weight(1f), onClick = { nav.navigate(Routes.slips(placeId)) })
                SecondaryButton(stringResource(R.string.remind), icon = Icons.Filled.Campaign, modifier = Modifier.weight(1f), onClick = { nav.navigate(Routes.remind(placeId)) })
            }
            ChoiceRow(listOf("all" to stringResource(R.string.all), "mine" to stringResource(R.string.mine), "unpaid" to stringResource(R.string.not_settled)),
                filter, { filter = it })
            if (all.isEmpty()) EmptyState(Icons.AutoMirrored.Filled.ReceiptLong, stringResource(R.string.no_bills_month),
                stringResource(if (manage) R.string.no_bills_month_manager else R.string.no_bills_month_member))
            else {
                val pending = all.filter { it.remainingPaisa > 0 }.sumOf { it.remainingPaisa }
                SmallLabel(stringResource(R.string.bills_summary, all.size, money(all.sumOf { it.totalPaisa }), money(pending)))
                SectionCard {
                    bills.forEachIndexed { i, b ->
                        if (i > 0) HorizontalDivider()
                        BillRow(b, showName = true, onClick = { nav.navigate(Routes.bill(b.id)) })
                    }
                }
            }
            Hint(stringResource(R.string.only_manager_marks))
            Spacer(Modifier.width(1.dp))
        }
    }
}
