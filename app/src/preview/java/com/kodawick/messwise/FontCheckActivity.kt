package com.kodawick.messwise

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiFoodBeverage
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodawick.messwise.ui.common.AppScreen
import com.kodawick.messwise.ui.common.BalanceText
import com.kodawick.messwise.ui.common.BigNumber
import com.kodawick.messwise.ui.common.Hint
import com.kodawick.messwise.ui.common.InfoRow
import com.kodawick.messwise.ui.common.PrimaryButton
import com.kodawick.messwise.ui.common.ScrollColumn
import com.kodawick.messwise.ui.common.SectionCard
import com.kodawick.messwise.ui.common.SmallLabel
import com.kodawick.messwise.ui.common.StatusChip
import com.kodawick.messwise.ui.common.money
import com.kodawick.messwise.ui.theme.MesswiseTheme

/** Test build only: the home screen's cards with sample figures, for checking the Urdu fonts on an emulator. */
class FontCheckActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MesswiseTheme { FontCheck() } }
    }
}

@androidx.compose.runtime.Composable
private fun FontCheck() {
    AppScreen("MSD Mushaf", onBack = null) { pad ->
        ScrollColumn(pad) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.my_tea_today), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(pluralStringResource(R.plurals.cups_marked, 3, 3), style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        SmallLabel(stringResource(R.string.manager_counted, 3))
                    }
                    Button(onClick = {}, modifier = Modifier.height(64.dp), shape = MaterialTheme.shapes.large,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)) {
                        Icon(Icons.Filled.EmojiFoodBeverage, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_tea), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Hint(stringResource(R.string.add_tea_private))
            }
            SectionCard(stringResource(R.string.my_bills)) {
                BalanceText(948000, big = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.month_bill_title, "Oct 2026"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        SmallLabel(stringResource(R.string.bill_total_x, money(948000)))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(money(948000), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        StatusChip("pending")
                    }
                }
                PrimaryButton(stringResource(R.string.pay_now), icon = Icons.Filled.Payments, onClick = {})
            }
            SectionCard(stringResource(R.string.this_month_so_far, "Nov 2026")) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigNumber(stringResource(R.string.tea_rate_per_cup), money(3357), Modifier.weight(1f))
                    BigNumber(stringResource(R.string.cups_made), "420", Modifier.weight(1f))
                }
                InfoRow(stringResource(R.string.tea_spent), money(1410000))
                InfoRow(stringResource(R.string.kitchen_spent), money(3600000))
                SmallLabel(stringResource(R.string.my_share_so_far))
                InfoRow(pluralStringResource(R.plurals.my_cups, 82, 82), money(275286))
                InfoRow(stringResource(R.string.kitchen_share), money(600000))
                InfoRow(stringResource(R.string.so_far_total), money(1015286), strong = true)
                Hint(stringResource(R.string.estimate_note))
            }
        }
    }
}
