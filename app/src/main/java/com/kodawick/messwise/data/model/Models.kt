package com.kodawick.messwise.data.model

import kotlinx.serialization.Serializable

// Shapes of the JSON the mw_* functions return. Field names are camelCase here and snake_case on the wire.

@Serializable
data class Profile(val userId: String = "", val fullName: String = "", val email: String? = null, val language: String = "en", val budgetPaisa: Long? = null)

@Serializable
data class PlaceItem(
    val id: String, val name: String = "", val kind: String = "hostel", val address: String = "", val logoPath: String? = null,
    val memberId: String = "", val role: String = "member", val isMainManager: Boolean = false, val type: String = "resident",
    val pendingPaisa: Long = 0,
)

@Serializable
data class PendingRequest(val id: String, val placeName: String = "", val createdAt: String? = null)

@Serializable
data class Me(val profile: Profile = Profile(), val places: List<PlaceItem> = emptyList(), val pendingRequests: List<PendingRequest> = emptyList(), val unread: Int = 0)

@Serializable
data class Leave(val id: String, val fromDate: String = "", val toDate: String? = null, val note: String = "")

@Serializable
data class Member(
    val id: String, val name: String = "", val role: String = "member", val isMainManager: Boolean = false, val type: String = "resident",
    val onTea: Boolean = true, val onKitchen: Boolean = true, val status: String = "active", val joinedOn: String? = null,
    val leftOn: String? = null, val joinOrder: Int = 0, val linked: Boolean = false, val isMe: Boolean = false,
    val onLeaveToday: Boolean = false, val leave: Leave? = null,
) {
    val isAdmin get() = role == "owner" || role == "manager"
}

@Serializable
data class Can(val manage: Boolean = false, val owner: Boolean = false, val soleAdmin: Boolean = false,
               private val money: Boolean? = null, val soleManager: Boolean = false) {
    /** Entries, bills and payments: Canteen Managers only (an owner only while also the main Canteen Manager). */
    val handlesMoney get() = money ?: manage
}

@Serializable
data class Manager(val id: String, val name: String = "", val role: String = "", val isMainManager: Boolean = false)

@Serializable
data class Place(
    val id: String, val name: String = "", val address: String = "", val kind: String = "hostel", val logoPath: String? = null,
    val joinCode: String? = null, val roundToRupee: Boolean = true, val partyOfficeSplit: String = "all",
    val me: Member, val can: Can = Can(), val managers: List<Manager> = emptyList(), val memberCount: Int = 0,
) {
    val mainManager get() = managers.firstOrNull { it.isMainManager } ?: managers.firstOrNull()
}

@Serializable
data class TeaSummary(
    val totalPaisa: Long = 0, val cupsMembers: Int = 0, val cupsPersonal: Int = 0, val cupsOffice: Int = 0, val cupsTotal: Int = 0,
    val teaMembers: Int = 0, val rate: Double? = null, val officeSharePaisa: Long? = null,
)

@Serializable
data class KitchenSummary(val sharedPaisa: Long = 0, val memberDays: Int = 0, val perDay: Double? = null, val mealsPaisa: Long = 0)

@Serializable
data class MonthRow(
    val memberId: String, val name: String = "", val linked: Boolean = false, val status: String = "active", val type: String = "resident",
    val cups: Int = 0, val guestCups: Int = 0, val teaPaisa: Long = 0, val kitchenDays: Int? = null, val kitchenSharedPaisa: Long = 0,
    val mealsPaisa: Long = 0, val mealsCount: Int = 0, val partyPaisa: Long = 0, val creditPaisa: Long = 0,
    val chargesPaisa: Long = 0, val totalPaisa: Long = 0,
) {
    val kitchenPaisa get() = kitchenSharedPaisa + mealsPaisa
}

@Serializable
data class BillDetail(
    val cups: Int? = null, val guestCups: Int? = null, val kitchenDays: Int? = null, val mealsCount: Int? = null,
    val teaRate: Double? = null, val perDay: Double? = null, val title: String? = null, val day: String? = null,
)

@Serializable
data class Bill(
    val id: String, val placeId: String = "", val memberId: String = "", val memberName: String = "", val isMainManager: Boolean = false,
    val kind: String = "month", val month: String = "", val partyId: String? = null, val partyTitle: String? = null, val partyDay: String? = null,
    val teaPaisa: Long = 0, val kitchenSharedPaisa: Long = 0, val mealsPaisa: Long = 0, val partyPaisa: Long = 0, val creditPaisa: Long = 0,
    val totalPaisa: Long = 0, val settledPaisa: Long = 0, val waitingPaisa: Long = 0, val remainingPaisa: Long = 0,
    val status: String = "pending", val detail: BillDetail = BillDetail(), val updatedAt: String? = null,
)

/** Which parts of a month the viewer may see (server rule since 27 Nov 2026). An older server sends none: all true. */
@Serializable
data class Sees(val tea: Boolean = true, val kitchen: Boolean = true, val meals: Boolean = true, val everyone: Boolean = true)

@Serializable
data class MonthView(
    val month: String = "", val upto: String? = null, val status: String = "open", val tea: TeaSummary = TeaSummary(),
    val kitchen: KitchenSummary = KitchenSummary(), val partiesPaisa: Long = 0, val rows: List<MonthRow> = emptyList(),
    val warnings: List<String> = emptyList(), val billedAt: String? = null, val reopenReason: String? = null,
    val canBill: Boolean = false, val canReopen: Boolean = false, val bills: List<Bill> = emptyList(),
    val sees: Sees = Sees(),
)

@Serializable
data class MonthBrief(val month: String = "", val status: String = "open", val tea: TeaSummary = TeaSummary(), val kitchen: KitchenSummary = KitchenSummary(),
    val warnings: List<String> = emptyList(), val sees: Sees = Sees())

@Serializable
data class TeaToday(val official: Int = 0, val myOfficial: Int = 0, val myMarks: Int = 0)

@Serializable
data class AdminInfo(val slipsWaiting: Int = 0, val openQuestions: Int = 0, val joinRequests: Int = 0, val unbilledMonths: List<String> = emptyList())

@Serializable
data class Dashboard(
    val place: Place, val today: String = "", val month: MonthBrief = MonthBrief(), val mySoFar: MonthRow? = null,
    val myBills: List<Bill> = emptyList(), val advancePaisa: Long = 0, val pending: List<Bill> = emptyList(),
    val teaToday: TeaToday = TeaToday(), val mealsToday: Int = 0, val onLeaveToday: List<String> = emptyList(),
    val admin: AdminInfo? = null, val unread: Int = 0,
)

// ---------------------------------------------------------------- entries

@Serializable
data class Payer(val memberId: String, val name: String = "", val amountPaisa: Long = 0, val receivedPaisa: Long = 0)

@Serializable
data class ShareLine(val memberId: String, val name: String = "", val sharePaisa: Long = 0)

@Serializable
data class Person(val subject: String = "member", val memberId: String? = null, val name: String? = null, val units: Double = 1.0, val exactPaisa: Long? = null, val heads: Int = 1)

@Serializable
data class Version(val version: Int = 0, val reason: String = "", val changedAt: String? = null, val changedByName: String? = null)

@Serializable
data class QuestionBrief(val id: String, val text: String = "", val status: String = "open", val answer: String? = null, val askedBy: String? = null, val createdAt: String? = null)

/** A purchase (Tea or Kitchen Bill), a meal or a party. */
@Serializable
data class Entry(
    val type: String = "purchase", val id: String, val placeId: String = "", val bill: String? = null, val item: String? = null,
    val kind: String? = null, val title: String? = null, val amountPaisa: Long = 0, val day: String = "", val note: String = "",
    val receiptPath: String? = null, val status: String = "active", val voidReason: String? = null, val version: Int = 1,
    val createdByName: String? = null, val createdAt: String? = null, val updatedAt: String? = null,
    val payers: List<Payer> = emptyList(), val people: List<Person> = emptyList(), val shares: List<ShareLine> = emptyList(),
    val officeSplit: String? = null, val addToMonth: Boolean = false, val heads: Int = 0, val perHeadPaisa: Long? = null,
    val walkInHeads: Int = 0, val walkInEachPaisa: Long = 0, val walkInPaisa: Long = 0,
    val bills: List<Bill> = emptyList(), val versions: List<Version> = emptyList(), val questions: List<QuestionBrief> = emptyList(),
    val canEdit: Boolean = false,
) {
    val isVoid get() = status == "void"
}

@Serializable
data class EntryList(val month: String = "", val items: List<Entry> = emptyList())

// ---------------------------------------------------------------- tea

@Serializable
data class TeaRow(val memberId: String, val name: String = "", val onTea: Boolean = true, val onLeave: Boolean = false, val cups: Int = 0, val guestCups: Int = 0)

@Serializable
data class TeaDay(
    val day: String = "", val monthStatus: String = "open", val canEdit: Boolean = false, val rows: List<TeaRow> = emptyList(),
    val officeCups: Int = 0, val dayTotal: Int = 0, val month: TeaSummary = TeaSummary(),
)

@Serializable
data class MyTeaToday(val day: String = "", val todayCups: Int = 0)

@Serializable
data class MyTeaDay(val day: String, val mine: Int = 0, val official: Int = 0)

@Serializable
data class MyTea(val month: String = "", val days: List<MyTeaDay> = emptyList(), val mineTotal: Int = 0, val officialTotal: Int = 0, val tea: TeaSummary = TeaSummary())

// ---------------------------------------------------------------- bills and payments

@Serializable
data class Payment(
    val id: String, val direction: String = "in", val amountPaisa: Long = 0, val method: String = "cash", val slipPath: String? = null,
    val hasSlip: Boolean = false, val note: String = "", val status: String = "sent", val reason: String? = null,
    val createdAt: String? = null, val decidedAt: String? = null, val decidedByName: String? = null,
)

@Serializable
data class MealLine(val id: String, val day: String = "", val kind: String = "", val title: String = "", val sharePaisa: Long = 0)

@Serializable
data class PaidLine(val type: String = "", val id: String, val title: String = "", val bill: String? = null, val day: String = "", val amountPaisa: Long = 0, val receivedPaisa: Long = 0)

@Serializable
data class BillExtras(
    val isMine: Boolean = false, val canMark: Boolean = false, val meals: List<MealLine>? = null,
    val paidForGroup: List<PaidLine> = emptyList(), val payments: List<Payment> = emptyList(),
)

data class BillFull(val bill: Bill, val extras: BillExtras)

@Serializable
data class WaitingPayment(
    val id: String, val billId: String? = null, val memberId: String = "", val memberName: String = "", val amountPaisa: Long = 0,
    val method: String = "", val slipPath: String? = null, val note: String = "", val createdAt: String? = null,
    val bill: Bill? = null, val canMark: Boolean = false,
)

@Serializable
data class RemindResult(val sent: Int = 0, val skipped: Int = 0)

// ---------------------------------------------------------------- members

@Serializable
data class JoinRequest(val id: String, val fullName: String = "", val createdAt: String? = null, val email: String? = null)

@Serializable
data class JoinResult(val status: String = "", val placeId: String = "", val placeName: String = "", val requestId: String? = null)

@Serializable
data class MemberExtras(val month: String = "", val monthRow: MonthRow? = null, val leaves: List<Leave> = emptyList(), val bills: List<Bill> = emptyList())

data class MemberFull(val member: Member, val extras: MemberExtras)

// ---------------------------------------------------------------- questions, notifications, activity

@Serializable
data class Question(
    val id: String, val entity: String = "", val entityId: String? = null, val month: String? = null, val text: String = "",
    val tally: MyTea? = null, val status: String = "open", val answer: String? = null, val askedBy: String = "",
    val memberId: String = "", val createdAt: String? = null, val answeredAt: String? = null,
)

@Serializable
data class Notice(
    val id: Long, val placeId: String? = null, val placeName: String? = null, val kind: String = "", val title: String = "",
    val body: String = "", val createdAt: String? = null, val read: Boolean = false,
)

@Serializable
data class ActivityItem(val id: Long, val actorName: String = "", val action: String = "", val entity: String = "", val entityId: String? = null, val summary: String = "", val at: String? = null)

// ---------------------------------------------------------------- reports

@Serializable
data class ReportPlace(val name: String = "", val address: String = "", val kind: String = "")

@Serializable
data class TeaCount(val day: String = "", val subject: String = "member", val memberId: String? = null, val cups: Int = 0)

@Serializable
data class ReportData(
    val place: ReportPlace = ReportPlace(), val generatedAt: String? = null, val generatedBy: String = "", val meMemberId: String? = null, val month: MonthView = MonthView(),
    val teaPurchases: List<Entry> = emptyList(), val kitchenPurchases: List<Entry> = emptyList(), val meals: List<Entry> = emptyList(),
    val parties: List<Entry> = emptyList(), val teaDays: List<TeaCount> = emptyList(),
)

// ---------------------------------------------------------------- private book

@Serializable
data class PersonalItem(val id: String, val amountPaisa: Long = 0, val category: String = "", val note: String = "", val day: String = "", val receiptPath: String? = null)

@Serializable
data class CategoryTotal(val category: String = "", val amountPaisa: Long = 0)

@Serializable
data class MessCharge(val placeId: String = "", val placeName: String = "", val chargesPaisa: Long = 0)

@Serializable
data class Personal(
    val month: String = "", val budgetPaisa: Long? = null, val items: List<PersonalItem> = emptyList(),
    val byCategory: List<CategoryTotal> = emptyList(), val personalTotalPaisa: Long = 0, val mess: List<MessCharge> = emptyList(),
)
