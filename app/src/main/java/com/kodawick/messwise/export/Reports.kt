package com.kodawick.messwise.export

import com.kodawick.messwise.core.Money
import com.kodawick.messwise.core.Sheet
import com.kodawick.messwise.core.XlsxWriter
import com.kodawick.messwise.data.model.Entry
import com.kodawick.messwise.data.model.MonthRow
import com.kodawick.messwise.data.model.ReportData
import com.kodawick.messwise.data.model.Sees
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The six downloadable reports, each as PDF or Excel:
 * Tea expenses, Teas per person, Tea bills, Kitchen expenses (other than tea), Kitchen bill on all, Kitchen bill per person.
 */
enum class ReportKind(val title: String, val file: String) {
    TEA_EXPENSES("Total Tea Expenses", "Tea_Expenses"),
    TEA_CUPS("Teas per Person", "Teas_per_Person"),
    TEA_BILLS("Tea Bills", "Tea_Bills"),
    KITCHEN_EXPENSES("Total Kitchen Expenses (other than tea)", "Kitchen_Expenses"),
    KITCHEN_ALL("Kitchen Bill on All", "Kitchen_Bill_All"),
    KITCHEN_EACH("Kitchen Bill per Person", "Kitchen_Bill_Each"),
}

object Reports {
    private val pk = ZoneId.of("Asia/Karachi")
    private fun rs(p: Long) = Money.format(p)
    private fun num(p: Long) = p / 100.0
    private fun date(iso: String?) = try { LocalDate.parse(iso!!.take(10)).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) } catch (_: Exception) { iso ?: "" }
    private fun month(iso: String?) = try { LocalDate.parse(iso!!.take(10)).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)) } catch (_: Exception) { iso ?: "" }
    private fun stamp(iso: String?) = try { OffsetDateTime.parse(iso).atZoneSameInstant(pk).format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)) }
        catch (_: Exception) { OffsetDateTime.now(pk).format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)) }
    private fun rate(r: Double?) = r?.let { Math.round(it * 100) }
    private fun payers(e: Entry) = e.payers.joinToString(", ") { it.name }
    private fun mealName(e: Entry) = (e.kind ?: "meal").replaceFirstChar { it.uppercase() } + (e.title?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")

    fun fileName(kind: ReportKind?, r: ReportData, ext: String): String {
        val m = month(r.month.month).replace(' ', '_')
        return Share.safeName(r.place.name) + "_" + (kind?.file ?: "All_Reports") + "_" + m + "." + ext
    }

    private fun writer(kind: ReportKind, r: ReportData) = PdfWriter(kind.title, month(r.month.month), r.place.name).apply {
        generated = "Made " + stamp(r.generatedAt) + (if (r.generatedBy.isNotBlank()) " by " + r.generatedBy else "")
        newPage()
        val status = if (r.month.status == "billed") "Final bill (locked)" else "Open month, figures so far up to " + date(r.month.upto)
        para(r.place.name + (if (r.place.address.isNotBlank()) " · " + r.place.address else "") + " · " + status)
    }

    private fun members(r: ReportData): List<MonthRow> = r.month.rows

    /** Managers see everything; a member's report holds only what they are part of (server rule). */
    private fun full(r: ReportData) = r.month.sees.everyone
    private fun mine(r: ReportData) = r.month.rows.filter { it.memberId == r.meMemberId }

    /** The reports this viewer may make: tea reports for tea drinkers, kitchen reports for kitchen members. */
    fun visible(sees: Sees): List<ReportKind> = ReportKind.entries.filter { if (it.name.startsWith("TEA")) sees.tea else sees.kitchen }

    // ---------------------------------------------------------------- PDF

    fun pdf(kind: ReportKind, r: ReportData): ByteArray = writer(kind, r).run {
        val m = r.month
        val t = m.tea
        val k = m.kitchen
        when (kind) {
            ReportKind.TEA_EXPENSES -> {
                box {
                    row("Tea expenses (milk, tea, sugar...)", rs(t.totalPaisa), true)
                    row("Cups made this month", t.cupsTotal.toString())
                    row("Rate per cup", rate(t.rate)?.let { rs(it) } ?: "—", true)
                }
                para("Rate per cup = total tea expenses ÷ total cups made (members, their guests and office guests).")
                heading("Tea purchases")
                table(listOf("Date", "Item", "Paid by", "Amount"), listOf(1.2f, 2.2f, 1.8f, 1.1f),
                    r.teaPurchases.map { listOf(date(it.day), it.item ?: "", payers(it), rs(it.amountPaisa)) }, right = setOf(3))
                row("Total", rs(r.teaPurchases.sumOf { it.amountPaisa }), true)
            }
            ReportKind.TEA_CUPS -> {
                box {
                    row("Members' own cups", t.cupsMembers.toString())
                    row("Their personal guests' cups", t.cupsPersonal.toString())
                    row("Office guests' cups (shared by all on tea)", t.cupsOffice.toString())
                    row("Total cups", t.cupsTotal.toString(), true)
                }
                heading("Cups per person")
                table(listOf("Name", "Own cups", "Guest cups", "Total"), listOf(2.4f, 1f, 1f, 1f),
                    members(r).filter { it.cups + it.guestCups > 0 || it.teaPaisa > 0 }.map { listOf(it.name, it.cups.toString(), it.guestCups.toString(), (it.cups + it.guestCups).toString()) },
                    right = setOf(1, 2, 3))
                val days = r.teaDays.groupBy { it.day }.toSortedMap()
                if (days.isNotEmpty()) {
                    heading("Cups per day")
                    table(listOf("Date", "Members", "Personal guests", "Office guests", "Total"), listOf(1.4f, 1f, 1.2f, 1.1f, 0.9f),
                        days.map { (d, list) ->
                            val mem = list.filter { it.subject == "member" }.sumOf { it.cups }
                            val pg = list.filter { it.subject == "personal_guest" }.sumOf { it.cups }
                            val og = list.filter { it.subject == "office_guest" }.sumOf { it.cups }
                            listOf(date(d), mem.toString(), pg.toString(), og.toString(), (mem + pg + og).toString())
                        }, right = setOf(1, 2, 3, 4))
                }
            }
            ReportKind.TEA_BILLS -> {
                box {
                    row("Total tea expenses", rs(t.totalPaisa))
                    row("Total cups", t.cupsTotal.toString())
                    row("Rate per cup", rate(t.rate)?.let { rs(it) } ?: "—", true)
                    t.officeSharePaisa?.let { row("Office guests' tea, each member's share", rs(it)) }
                }
                para("Each person's tea bill = their cups (with their personal guests) × rate, plus an equal share of office guests' cups.")
                heading("Tea bill per person")
                table(listOf("Name", "Cups", "Tea bill"), listOf(2.6f, 1f, 1.3f),
                    members(r).filter { it.teaPaisa > 0 || it.cups + it.guestCups > 0 }.map { listOf(it.name, (it.cups + it.guestCups).toString(), rs(it.teaPaisa)) },
                    right = setOf(1, 2))
                row("Total tea bills", rs(members(r).sumOf { it.teaPaisa }), true)
            }
            ReportKind.KITCHEN_EXPENSES -> {
                if (full(r)) box {
                    row("Shared kitchen supplies", rs(k.sharedPaisa))
                    row("Meal bills", rs(k.mealsPaisa))
                    if (m.partiesPaisa > 0) row("Parties added to this month", rs(m.partiesPaisa))
                    row("Total kitchen expenses", rs(k.sharedPaisa + k.mealsPaisa + m.partiesPaisa), true)
                } else box {
                    row("Shared kitchen supplies", rs(k.sharedPaisa))
                    row("Meals you were part of", rs(r.meals.sumOf { it.amountPaisa }))
                    if (r.parties.isNotEmpty()) row("Parties you were part of", rs(r.parties.sumOf { it.amountPaisa }))
                }
                heading("Kitchen purchases (oil, flour, gas...)")
                table(listOf("Date", "Item", "Paid by", "Amount"), listOf(1.2f, 2.2f, 1.8f, 1.1f),
                    r.kitchenPurchases.map { listOf(date(it.day), it.item ?: "", payers(it), rs(it.amountPaisa)) }, right = setOf(3))
                heading("Meals")
                table(listOf("Date", "Meal", "Plates", "Paid by", "Amount"), listOf(1.2f, 1.8f, 0.8f, 1.6f, 1.1f),
                    r.meals.map { e -> listOf(date(e.day), mealName(e), e.people.sumOf { it.units }.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }, payers(e), rs(e.amountPaisa)) },
                    right = setOf(2, 4))
                if (r.parties.isNotEmpty()) {
                    heading("Parties and grand meals")
                    table(listOf("Date", "Title", "Heads", "Per head", "Total", "Billed"), listOf(1.1f, 1.8f, 0.7f, 1f, 1.1f, 1f),
                        r.parties.map { listOf(date(it.day), it.title ?: "", it.heads.toString(), it.perHeadPaisa?.let { p -> rs(p) } ?: "", rs(it.amountPaisa),
                            if (it.addToMonth) "In month" else "Separate") }, right = setOf(2, 3, 4))
                }
            }
            ReportKind.KITCHEN_ALL -> {
                box {
                    row("Shared kitchen supplies", rs(k.sharedPaisa))
                    row("Days present (all kitchen members)", k.memberDays.toString())
                    row("Cost per person per day", rate(k.perDay)?.let { rs(it) } ?: "—", true)
                    if (full(r)) row("Meal bills (split among those who ate)", rs(k.mealsPaisa))
                }
                para("Shared supplies are divided by days present: people on leave are not charged for those days, and day visitors are not on the Kitchen Bill. Meal bills go only to those who ate, by plates; personal guests go to their host and office guests to everyone present.")
                heading("Kitchen bill on all")
                if (full(r)) {
                    table(listOf("Name", "Days", "Supplies", "Meals", "Parties", "Total"), listOf(2f, 0.7f, 1.1f, 1.1f, 1f, 1.1f),
                        members(r).filter { it.kitchenPaisa > 0 || it.partyPaisa > 0 || it.kitchenDays != null }.map {
                            listOf(it.name, it.kitchenDays?.toString() ?: "—", rs(it.kitchenSharedPaisa), rs(it.mealsPaisa), rs(it.partyPaisa), rs(it.kitchenPaisa + it.partyPaisa))
                        }, right = setOf(1, 2, 3, 4, 5))
                    row("Total", rs(members(r).sumOf { it.kitchenPaisa + it.partyPaisa }), true)
                } else {
                    // Shared supplies are everyone's; meals and parties only show on each person's own bill.
                    table(listOf("Name", "Days", "Supplies"), listOf(2.6f, 0.8f, 1.3f),
                        members(r).filter { it.kitchenDays != null || it.kitchenSharedPaisa > 0 }.map {
                            listOf(it.name, it.kitchenDays?.toString() ?: "—", rs(it.kitchenSharedPaisa))
                        }, right = setOf(1, 2))
                    row("Total supplies", rs(members(r).sumOf { it.kitchenSharedPaisa }), true)
                }
            }
            ReportKind.KITCHEN_EACH -> {
                para("One section per person: their kitchen share, meals, what they paid for the group, and their full month bill.")
                (if (full(r)) members(r) else mine(r)).forEach { p ->
                    heading(p.name)
                    box {
                        p.kitchenDays?.let { row("Kitchen supplies: $it days × " + (rate(k.perDay)?.let { r2 -> rs(r2) } ?: "—"), rs(p.kitchenSharedPaisa)) }
                        if (p.mealsPaisa > 0) row("Meals (${p.mealsCount})", rs(p.mealsPaisa))
                        if (p.partyPaisa > 0) row("Parties", rs(p.partyPaisa))
                        row("Tea bill (${p.cups + p.guestCups} cups)", rs(p.teaPaisa))
                        if (p.creditPaisa > 0) row("Paid for the group (taken off)", "− " + rs(p.creditPaisa))
                        row(if (p.totalPaisa >= 0) "Month bill to pay" else "To get back", rs(kotlin.math.abs(p.totalPaisa)), true)
                    }
                }
            }
        }
        bytes()
    }

    // ---------------------------------------------------------------- Excel

    private fun sheet(kind: ReportKind, r: ReportData): List<Sheet> {
        val m = r.month
        val t = m.tea
        val k = m.kitchen
        val rows = members(r)
        return when (kind) {
            ReportKind.TEA_EXPENSES -> listOf(Sheet("Tea expenses", listOf("Date", "Item", "Paid by", "Amount (Rs)"),
                r.teaPurchases.map { listOf(date(it.day), it.item ?: "", payers(it), num(it.amountPaisa)) } +
                    listOf(listOf("", "Total", "", num(r.teaPurchases.sumOf { it.amountPaisa })),
                        listOf("", "Cups made", "", t.cupsTotal), listOf("", "Rate per cup", "", rate(t.rate)?.let { num(it) } ?: ""))))
            ReportKind.TEA_CUPS -> {
                val days = r.teaDays.map { it.day }.distinct().sorted()
                val byMember = r.teaDays.filter { it.subject != "office_guest" }.groupBy { it.memberId }
                listOf(
                    Sheet("Teas per person", listOf("Name", "Own cups", "Guest cups", "Total"),
                        rows.map { listOf(it.name, it.cups, it.guestCups, it.cups + it.guestCups) } +
                            listOf(listOf("Office guests", "", t.cupsOffice, t.cupsOffice), listOf("Total", t.cupsMembers, t.cupsPersonal, t.cupsTotal))),
                    Sheet("Cups by day", listOf("Name") + days.map { date(it) } + listOf("Total"),
                        rows.map { p ->
                            val list = byMember[p.memberId] ?: emptyList()
                            listOf<Any?>(p.name) + days.map { d -> list.filter { it.day == d }.sumOf { it.cups } } + listOf(list.sumOf { it.cups })
                        } + listOf(listOf<Any?>("Office guests") + days.map { d -> r.teaDays.filter { it.day == d && it.subject == "office_guest" }.sumOf { it.cups } } +
                            listOf(t.cupsOffice))),
                )
            }
            ReportKind.TEA_BILLS -> listOf(Sheet("Tea bills", listOf("Name", "Cups", "Rate (Rs)", "Tea bill (Rs)"),
                rows.map { listOf(it.name, it.cups + it.guestCups, rate(t.rate)?.let { r2 -> num(r2) } ?: "", num(it.teaPaisa)) } +
                    listOf(listOf("Total", t.cupsTotal, "", num(rows.sumOf { it.teaPaisa })))))
            ReportKind.KITCHEN_EXPENSES -> listOf(
                Sheet("Kitchen purchases", listOf("Date", "Item", "Paid by", "Amount (Rs)"),
                    r.kitchenPurchases.map { listOf(date(it.day), it.item ?: "", payers(it), num(it.amountPaisa)) } +
                        listOf(listOf("", "Total", "", num(r.kitchenPurchases.sumOf { it.amountPaisa })))),
                Sheet(if (full(r)) "Meals" else "My meals", listOf("Date", "Meal", "Plates", "Paid by", "Amount (Rs)"),
                    r.meals.map { e -> listOf(date(e.day), mealName(e), e.people.sumOf { it.units }, payers(e), num(e.amountPaisa)) } +
                        listOf(listOf("", "Total", "", "", num(r.meals.sumOf { it.amountPaisa })))),
                Sheet(if (full(r)) "Parties" else "My parties", listOf("Date", "Title", "Heads", "Per head (Rs)", "Total (Rs)", "Billed"),
                    r.parties.map { listOf(date(it.day), it.title ?: "", it.heads, it.perHeadPaisa?.let { p -> num(p) } ?: "", num(it.amountPaisa), if (it.addToMonth) "In month" else "Separate") }),
            )
            ReportKind.KITCHEN_ALL -> if (!full(r)) listOf(Sheet("Kitchen bill on all", listOf("Name", "Days", "Supplies (Rs)"),
                rows.filter { it.kitchenDays != null || it.kitchenSharedPaisa > 0 }.map { listOf(it.name, it.kitchenDays ?: "", num(it.kitchenSharedPaisa)) } +
                    listOf(listOf("Total", k.memberDays, num(k.sharedPaisa)), listOf("Per day", "", rate(k.perDay)?.let { num(it) } ?: ""))))
            else listOf(Sheet("Kitchen bill on all", listOf("Name", "Days", "Supplies (Rs)", "Meals (Rs)", "Parties (Rs)", "Total (Rs)"),
                rows.map { listOf(it.name, it.kitchenDays ?: "", num(it.kitchenSharedPaisa), num(it.mealsPaisa), num(it.partyPaisa), num(it.kitchenPaisa + it.partyPaisa)) } +
                    listOf(listOf("Total", k.memberDays, num(k.sharedPaisa), num(k.mealsPaisa), num(m.partiesPaisa), num(rows.sumOf { it.kitchenPaisa + it.partyPaisa })),
                        listOf("Per day", "", rate(k.perDay)?.let { num(it) } ?: "", "", "", ""))))
            ReportKind.KITCHEN_EACH -> listOf(Sheet("Bill per person", listOf("Name", "Kitchen days", "Supplies (Rs)", "Meals", "Meals (Rs)", "Parties (Rs)",
                "Cups", "Tea (Rs)", "Paid for group (Rs)", "Month bill (Rs)"),
                (if (full(r)) rows else mine(r)).map { listOf(it.name, it.kitchenDays ?: "", num(it.kitchenSharedPaisa), it.mealsCount, num(it.mealsPaisa), num(it.partyPaisa),
                    it.cups + it.guestCups, num(it.teaPaisa), num(it.creditPaisa), num(it.totalPaisa)) }))
        }
    }

    fun xlsx(kind: ReportKind, r: ReportData): ByteArray = XlsxWriter.toBytes(sheet(kind, r))

    /** All six reports in one workbook. */
    fun allXlsx(r: ReportData): ByteArray {
        val seen = mutableSetOf<String>()
        val sheets = visible(r.month.sees).flatMap { sheet(it, r) }.filter { seen.add(it.name) }
        return XlsxWriter.toBytes(sheets)
    }
}
