package com.kodawick.messwise.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kodawick.messwise.R
import com.kodawick.messwise.ui.common.rememberLoad
import com.kodawick.messwise.data.Graph
import com.kodawick.messwise.data.model.ReportData
import com.kodawick.messwise.export.ReportKind
import com.kodawick.messwise.export.Reports
import com.kodawick.messwise.export.Share
import com.kodawick.messwise.ui.common.AppScreen
import com.kodawick.messwise.ui.common.Hint
import com.kodawick.messwise.ui.common.LocalFeedback
import com.kodawick.messwise.ui.common.MonthSwitcher
import com.kodawick.messwise.ui.common.PrimaryButton
import com.kodawick.messwise.ui.common.ScrollColumn
import com.kodawick.messwise.ui.common.SecondaryButton
import com.kodawick.messwise.ui.common.SectionCard
import com.kodawick.messwise.ui.common.SmallLabel
import com.kodawick.messwise.ui.common.plainError
import com.kodawick.messwise.ui.common.thisMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LABELS = mapOf(
    ReportKind.TEA_EXPENSES to (R.string.rep_tea_expenses to R.string.rep_tea_expenses_d),
    ReportKind.TEA_CUPS to (R.string.rep_tea_cups to R.string.rep_tea_cups_d),
    ReportKind.TEA_BILLS to (R.string.rep_tea_bills to R.string.rep_tea_bills_d),
    ReportKind.KITCHEN_EXPENSES to (R.string.rep_kitchen_expenses to R.string.rep_kitchen_expenses_d),
    ReportKind.KITCHEN_ALL to (R.string.rep_kitchen_all to R.string.rep_kitchen_all_d),
    ReportKind.KITCHEN_EACH to (R.string.rep_kitchen_each to R.string.rep_kitchen_each_d),
)

/** The six reports for any month, as PDF or Excel, shared or saved to Downloads. Made on the phone. */
@Composable
fun ReportsScreen(placeId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalFeedback.current?.snackbar
    var month by rememberSaveable { mutableStateOf(thisMonth()) }
    var busy by rememberSaveable { mutableStateOf<String?>(null) }
    var save by rememberSaveable { mutableStateOf(false) }

    fun make(key: String, build: (ReportData) -> Pair<String, ByteArray>, mime: String) {
        if (busy != null) return
        scope.launch {
            busy = key
            try {
                val data = Graph.repo.report(placeId, month)
                val (name, bytes) = withContext(Dispatchers.Default) { build(data) }
                if (save) {
                    val ok = Share.saveToDownloads(ctx, name, bytes, mime)
                    snack?.showSnackbar(ctx.getString(if (ok) R.string.saved_to_downloads else R.string.could_not_save))
                } else Share.send(ctx, name, bytes, mime)
            } catch (e: Exception) {
                snack?.showSnackbar(plainError(ctx, e))
            } finally { busy = null }
        }
    }

    AppScreen(stringResource(R.string.reports), onBack = onBack) { pad ->
        ScrollColumn(pad) {
            MonthSwitcher(month) { month = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.kodawick.messwise.ui.common.ChoiceRow(listOf(false to stringResource(R.string.share_file), true to stringResource(R.string.save_downloads)), save, { save = it })
            }
            // Only the reports for bills this person is on (managers get all six).
            val mv = rememberLoad("month", placeId, month, cached = { Graph.repo.monthCached(placeId, month) }) { Graph.repo.month(placeId, month) }
            val sees = mv.data?.sees ?: Graph.repo.dashboardCached(placeId)?.month?.sees
            (sees?.let { Reports.visible(it) } ?: emptyList()).forEach { k ->
                val (title, desc) = LABELS.getValue(k)
                SectionCard {
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    SmallLabel(stringResource(desc))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(if (busy == k.name + "pdf") stringResource(R.string.making) else "PDF", icon = Icons.Filled.PictureAsPdf,
                            modifier = Modifier.weight(1f).testTag("pdf_${k.name}"), enabled = busy == null,
                            onClick = { make(k.name + "pdf", { r -> Reports.fileName(k, r, "pdf") to Reports.pdf(k, r) }, Share.PDF) })
                        SecondaryButton(if (busy == k.name + "xlsx") stringResource(R.string.making) else "Excel", icon = Icons.Filled.TableChart,
                            modifier = Modifier.weight(1f).testTag("xlsx_${k.name}"), enabled = busy == null,
                            onClick = { make(k.name + "xlsx", { r -> Reports.fileName(k, r, "xlsx") to Reports.xlsx(k, r) }, Share.XLSX) })
                    }
                }
            }
            PrimaryButton(stringResource(R.string.all_reports_excel), icon = Icons.Filled.Download, busy = busy == "all", enabled = busy == null,
                onClick = { make("all", { r -> Reports.fileName(null, r, "xlsx") to Reports.allXlsx(r) }, Share.XLSX) })
            Hint(stringResource(R.string.reports_hint))
        }
    }
}
