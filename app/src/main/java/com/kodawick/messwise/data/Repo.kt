package com.kodawick.messwise.data

import com.kodawick.messwise.data.model.ActivityItem
import com.kodawick.messwise.data.model.Bill
import com.kodawick.messwise.data.model.BillExtras
import com.kodawick.messwise.data.model.BillFull
import com.kodawick.messwise.data.model.Dashboard
import com.kodawick.messwise.data.model.Entry
import com.kodawick.messwise.data.model.EntryList
import com.kodawick.messwise.data.model.JoinRequest
import com.kodawick.messwise.data.model.JoinResult
import com.kodawick.messwise.data.model.Me
import com.kodawick.messwise.data.model.Member
import com.kodawick.messwise.data.model.MemberExtras
import com.kodawick.messwise.data.model.MemberFull
import com.kodawick.messwise.data.model.MonthView
import com.kodawick.messwise.data.model.MyTea
import com.kodawick.messwise.data.model.MyTeaToday
import com.kodawick.messwise.data.model.Notice
import com.kodawick.messwise.data.model.Personal
import com.kodawick.messwise.data.model.Place
import com.kodawick.messwise.data.model.Question
import com.kodawick.messwise.data.model.RemindResult
import com.kodawick.messwise.data.model.ReportData
import com.kodawick.messwise.data.model.TeaDay
import com.kodawick.messwise.data.model.WaitingPayment
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One typed call per server function. Reads that screens show offline are cached. */
class Repo(private val api: Api) {

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to toJson(v) })

    @Suppress("UNCHECKED_CAST")
    fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject((v as Map<String, Any?>).mapValues { toJson(it.value) })
        is List<*> -> JsonArray(v.map { toJson(it) })
        else -> JsonPrimitive(v.toString())
    }

    // ---------------------------------------------------------------- account
    suspend fun me(): Me = api.rpc("mw_me", JsonObject(emptyMap()), Me.serializer(), "me")
    fun meCached(): Me? = api.cached("me", Me.serializer())
    suspend fun profileUpdate(name: String?, language: String?): Me =
        api.rpc("mw_profile_update", args("p" to mapOf("full_name" to name, "language" to language).filterValues { it != null }), Me.serializer(), "me")
    suspend fun accountDelete() { api.rpcRaw("mw_account_delete", JsonObject(emptyMap())) }

    // ---------------------------------------------------------------- places
    suspend fun placeCreate(name: String, address: String, kind: String, myName: String, ownerType: String): Place =
        api.rpc("mw_place_create", args("p" to mapOf("name" to name, "address" to address, "kind" to kind, "my_name" to myName, "owner_type" to ownerType)), Place.serializer())
    suspend fun place(id: String): Place = api.rpc("mw_place_get", args("p_place" to id), Place.serializer(), "place_$id")
    fun placeCached(id: String): Place? = api.cached("place_$id", Place.serializer())
    suspend fun placeUpdate(id: String, p: Map<String, Any?>): Place = api.rpc("mw_place_update", args("p_place" to id, "p" to p), Place.serializer(), "place_$id")
    suspend fun newCode(id: String): Place = api.rpc("mw_place_new_code", args("p_place" to id), Place.serializer(), "place_$id")
    suspend fun join(code: String): JoinResult = api.rpc("mw_join", args("p_code" to code), JoinResult.serializer())
    suspend fun joinRequests(placeId: String): List<JoinRequest> = api.rpc("mw_join_requests", args("p_place" to placeId), ListSerializer(JoinRequest.serializer()))
    suspend fun joinDecide(requestId: String, approve: Boolean, type: String, linkMemberId: String?): List<JoinRequest> =
        api.rpc("mw_join_decide", args("p_request" to requestId, "p" to mapOf("approve" to approve, "type" to type, "link_member_id" to linkMemberId)), ListSerializer(JoinRequest.serializer()))

    // ---------------------------------------------------------------- dashboard and members
    suspend fun dashboard(placeId: String): Dashboard = api.rpc("mw_dashboard", args("p_place" to placeId), Dashboard.serializer(), "dash_$placeId")
    fun dashboardCached(placeId: String): Dashboard? = api.cached("dash_$placeId", Dashboard.serializer())
    suspend fun members(placeId: String): List<Member> = api.rpc("mw_members", args("p_place" to placeId), ListSerializer(Member.serializer()), "members_$placeId")
    fun membersCached(placeId: String): List<Member>? = api.cached("members_$placeId", ListSerializer(Member.serializer()))
    suspend fun memberAdd(placeId: String, name: String, type: String): Member = api.rpc("mw_member_add", args("p_place" to placeId, "p" to mapOf("name" to name, "type" to type)), Member.serializer())
    suspend fun memberUpdate(memberId: String, name: String?, type: String?): Member =
        api.rpc("mw_member_update", args("p_member" to memberId, "p" to mapOf("name" to name, "type" to type).filterValues { it != null }), Member.serializer())
    suspend fun memberRole(memberId: String, role: String, main: Boolean): Member = api.rpc("mw_member_role", args("p_member" to memberId, "p" to mapOf("role" to role, "main" to main)), Member.serializer())
    suspend fun ownerTransfer(memberId: String): Member = api.rpc("mw_owner_transfer", args("p_member" to memberId), Member.serializer())
    suspend fun memberLeavePlace(memberId: String, leftOn: String, writeOff: String?): Member =
        api.rpc("mw_member_leave_place", args("p_member" to memberId, "p" to mapOf("left_on" to leftOn, "write_off_reason" to writeOff)), Member.serializer())
    suspend fun member(memberId: String, month: String?): MemberFull {
        val raw = api.rpcRaw("mw_member_get", args("p_member" to memberId, "p_month" to month))
        return MemberFull(Http.json.decodeFromJsonElement(Member.serializer(), raw), Http.json.decodeFromJsonElement(MemberExtras.serializer(), raw))
    }
    suspend fun leaveAdd(memberId: String, from: String, to: String?, note: String): Member =
        api.rpc("mw_leave_add", args("p_member" to memberId, "p" to mapOf("from_date" to from, "to_date" to to, "note" to note)), Member.serializer())
    suspend fun leaveEnd(leaveId: String, backOn: String): Member = api.rpc("mw_leave_end", args("p_leave" to leaveId, "p_back_on" to backOn), Member.serializer())
    suspend fun leaveVoid(leaveId: String, reason: String): Member = api.rpc("mw_leave_void", args("p_leave" to leaveId, "p_reason" to reason), Member.serializer())

    // ---------------------------------------------------------------- entries
    suspend fun purchaseSave(p: Map<String, Any?>): Entry = api.rpc("mw_purchase_save", args("p" to p), Entry.serializer())
    suspend fun purchaseVoid(id: String, reason: String): Entry = api.rpc("mw_purchase_void", args("p_id" to id, "p_reason" to reason), Entry.serializer())
    suspend fun mealSave(p: Map<String, Any?>): Entry = api.rpc("mw_meal_save", args("p" to p), Entry.serializer())
    suspend fun mealVoid(id: String, reason: String): Entry = api.rpc("mw_meal_void", args("p_id" to id, "p_reason" to reason), Entry.serializer())
    suspend fun partySave(p: Map<String, Any?>): Entry = api.rpc("mw_party_save", args("p" to p), Entry.serializer())
    suspend fun partyVoid(id: String, reason: String): Entry = api.rpc("mw_party_void", args("p_id" to id, "p_reason" to reason), Entry.serializer())
    suspend fun voidEntry(type: String, id: String, reason: String): Entry = when (type) {
        "meal" -> mealVoid(id, reason)
        "party" -> partyVoid(id, reason)
        else -> purchaseVoid(id, reason)
    }
    suspend fun entries(placeId: String, month: String): EntryList = api.rpc("mw_entries", args("p_place" to placeId, "p_month" to month), EntryList.serializer(), "entries_${placeId}_$month")
    fun entriesCached(placeId: String, month: String): EntryList? = api.cached("entries_${placeId}_$month", EntryList.serializer())
    suspend fun entry(type: String, id: String): Entry = api.rpc("mw_entry_get", args("p_type" to type, "p_id" to id), Entry.serializer())

    // ---------------------------------------------------------------- tea
    suspend fun teaDay(placeId: String, day: String): TeaDay = api.rpc("mw_tea_day", args("p_place" to placeId, "p_day" to day), TeaDay.serializer())
    suspend fun teaSet(placeId: String, day: String, rows: List<Map<String, Any?>>): TeaDay = api.rpc("mw_tea_set", args("p_place" to placeId, "p_day" to day, "p_rows" to rows), TeaDay.serializer())
    suspend fun teaRound(placeId: String, day: String, memberIds: List<String>): TeaDay = api.rpc("mw_tea_round", args("p_place" to placeId, "p_day" to day, "p_members" to memberIds), TeaDay.serializer())
    suspend fun myTeaAdd(placeId: String, cups: Int = 1): MyTeaToday = api.rpc("mw_my_tea_add", args("p_place" to placeId, "p_cups" to cups), MyTeaToday.serializer())
    suspend fun myTeaUndo(placeId: String): MyTeaToday = api.rpc("mw_my_tea_undo", args("p_place" to placeId), MyTeaToday.serializer())
    suspend fun myTea(placeId: String, month: String): MyTea = api.rpc("mw_my_tea", args("p_place" to placeId, "p_month" to month), MyTea.serializer())

    // ---------------------------------------------------------------- months and bills
    suspend fun month(placeId: String, month: String): MonthView = api.rpc("mw_month", args("p_place" to placeId, "p_month" to month), MonthView.serializer(), "month_${placeId}_$month")
    fun monthCached(placeId: String, month: String): MonthView? = api.cached("month_${placeId}_$month", MonthView.serializer())
    suspend fun monthBill(placeId: String, month: String): MonthView = api.rpc("mw_month_bill", args("p_place" to placeId, "p_month" to month), MonthView.serializer())
    suspend fun monthReopen(placeId: String, month: String, reason: String): MonthView = api.rpc("mw_month_reopen", args("p_place" to placeId, "p_month" to month, "p_reason" to reason), MonthView.serializer())
    suspend fun bills(placeId: String, month: String?, unpaid: Boolean, memberId: String? = null): List<Bill> =
        api.rpc("mw_bills", args("p_place" to placeId, "p" to mapOf("month" to month, "unpaid" to unpaid, "member_id" to memberId)), ListSerializer(Bill.serializer()))
    suspend fun bill(billId: String): BillFull = decodeBill(api.rpcRaw("mw_bill_get", args("p_bill" to billId)))
    private fun decodeBill(raw: JsonElement) = BillFull(Http.json.decodeFromJsonElement(Bill.serializer(), raw), Http.json.decodeFromJsonElement(BillExtras.serializer(), raw))

    // ---------------------------------------------------------------- payments
    suspend fun paymentSend(placeId: String, billId: String?, amountPaisa: Long, method: String, slipPath: String?, note: String): JsonElement =
        api.rpcRaw("mw_payment_send", args("p" to mapOf("place_id" to placeId, "bill_id" to billId, "amount_paisa" to amountPaisa, "method" to method, "slip_path" to slipPath, "note" to note)))
    suspend fun paymentDecide(paymentId: String, accept: Boolean, amountPaisa: Long?, reason: String?): JsonElement =
        api.rpcRaw("mw_payment_decide", args("p_payment" to paymentId, "p" to mapOf("accept" to accept, "amount_paisa" to amountPaisa, "reason" to reason)))
    suspend fun paymentRecord(placeId: String, billId: String?, memberId: String?, direction: String, amountPaisa: Long, method: String, slipPath: String?, note: String): JsonElement =
        api.rpcRaw("mw_payment_record", args("p" to mapOf("place_id" to placeId, "bill_id" to billId, "member_id" to memberId, "direction" to direction,
            "amount_paisa" to amountPaisa, "method" to method, "slip_path" to slipPath, "note" to note)))
    suspend fun paymentsWaiting(placeId: String): List<WaitingPayment> = api.rpc("mw_payments_waiting", args("p_place" to placeId), ListSerializer(WaitingPayment.serializer()))
    suspend fun remind(placeId: String, memberIds: List<String>, note: String): RemindResult =
        api.rpc("mw_remind", args("p_place" to placeId, "p" to mapOf("member_ids" to memberIds, "note" to note)), RemindResult.serializer())
    suspend fun notice(placeId: String, text: String) { api.rpcRaw("mw_notice", args("p_place" to placeId, "p_text" to text)) }

    // ---------------------------------------------------------------- questions, notifications, history
    suspend fun ask(placeId: String, entity: String, entityId: String?, month: String?, text: String, shareTally: Boolean) {
        api.rpcRaw("mw_question_ask", args("p" to mapOf("place_id" to placeId, "entity" to entity, "entity_id" to entityId, "month" to month, "text" to text, "share_tally" to shareTally)))
    }
    suspend fun answer(questionId: String, text: String): List<Question> = api.rpc("mw_question_answer", args("p_id" to questionId, "p_answer" to text), ListSerializer(Question.serializer()))
    suspend fun questions(placeId: String): List<Question> = api.rpc("mw_questions", args("p_place" to placeId), ListSerializer(Question.serializer()))
    suspend fun notifications(before: Long? = null): List<Notice> = api.rpc("mw_notifications", args("p_before" to before), ListSerializer(Notice.serializer()), if (before == null) "notifications" else null)
    fun notificationsCached(): List<Notice>? = api.cached("notifications", ListSerializer(Notice.serializer()))
    suspend fun notificationsRead(ids: List<Long>?) { api.rpcRaw("mw_notifications_read", args("p_ids" to ids)) }
    suspend fun activity(placeId: String, before: Long? = null): List<ActivityItem> = api.rpc("mw_activity", args("p_place" to placeId, "p_before" to before), ListSerializer(ActivityItem.serializer()))

    // ---------------------------------------------------------------- reports and private book
    suspend fun report(placeId: String, month: String): ReportData = api.rpc("mw_report", args("p_place" to placeId, "p_month" to month), ReportData.serializer())
    suspend fun personal(month: String): Personal = api.rpc("mw_personal", args("p_month" to month), Personal.serializer(), "personal_$month")
    fun personalCached(month: String): Personal? = api.cached("personal_$month", Personal.serializer())
    suspend fun personalSave(id: String?, amountPaisa: Long, category: String, note: String, day: String, receiptPath: String?): Personal =
        api.rpc("mw_personal_save", args("p" to mapOf("id" to id, "amount_paisa" to amountPaisa, "category" to category, "note" to note, "day" to day, "receipt_path" to receiptPath)), Personal.serializer())
    suspend fun personalDelete(id: String): Personal = api.rpc("mw_personal_delete", args("p_id" to id), Personal.serializer())
    suspend fun budgetSet(paisa: Long?): Personal = api.rpc("mw_budget_set", args("p_paisa" to paisa), Personal.serializer())

    companion object {
        fun obj(vararg pairs: Pair<String, Any?>) = buildJsonObject { pairs.forEach { (k, v) -> if (v is String) put(k, v) } }
    }
}
