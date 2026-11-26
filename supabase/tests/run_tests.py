#!/usr/bin/env python3
"""End-to-end tests for the Messwise database API.

Runs against a local Postgres that mimics Supabase (tests/bootstrap.sql + the migrations). Every call is
a separate session acting as the "authenticated" role with a user id, the way the phone reaches Supabase.
The numbers are the worked examples in the Messwise blueprint (November: tea Rs 5,000 / 200 cups = Rs 25,
kitchen Rs 11,000 / 110 days = Rs 100 a day, lunch Rs 3,000 for 5, Grand Dinner Rs 13,500 for 9 heads).
"""
import json
import os
import subprocess
import sys
import uuid

PORT = os.environ.get("MW_PORT", "5545")
PSQL = ["psql", "-h", "/tmp/mwpg", "-p", PORT, "-d", "messwise", "-U", "postgres", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1"]
PASS = 0
FAIL = []
TODAY = {"v": "2026-11-30"}


class ApiError(Exception):
    def __init__(self, code, msg):
        super().__init__(f"{code}: {msg}")
        self.code = code
        self.msg = msg


def lit(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, (dict, list)):
        return "'" + json.dumps(v).replace("'", "''") + "'::jsonb"
    if isinstance(v, uuid.UUID):
        return f"'{v}'::uuid"
    return "'" + str(v).replace("'", "''") + "'"


def sql(stmt):
    r = subprocess.run(PSQL + ["-c", stmt], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip())
    return r.stdout.strip()


def as_user(uid, stmt, today=None):
    t = today or TODAY["v"]
    pre = f"set role authenticated; set request.jwt.claim.sub = '{uid}'; set messwise.test_today = '{t}';"
    return subprocess.run(PSQL + ["-c", pre + stmt], capture_output=True, text=True)


def call(uid, fn, *args, today=None, cast=""):
    r = as_user(uid, f"select public.{fn}({', '.join(lit(a) for a in args)}){cast};", today)
    if r.returncode != 0:
        err = r.stderr.strip()
        if "MESSWISE_" in err:
            code, msg = err.split("MESSWISE_", 1)[1].split(":", 1)
            raise ApiError(code, msg.strip().splitlines()[0])
        raise ApiError("SQL", err)
    out = r.stdout.strip()
    return json.loads(out) if out else None


def check(name, cond, info=""):
    global PASS
    if cond:
        PASS += 1
    else:
        FAIL.append(f"{name} {info}")
        print(f"  FAIL {name} {info}")


def expect_err(name, code, f, *a, **k):
    try:
        f(*a, **k)
        check(name, False, f"expected {code}, got success")
    except ApiError as e:
        check(name, e.code == code, f"expected {code}, got {e.code}: {e.msg}")


def make_user(name):
    u = uuid.uuid4()
    sql(f"insert into auth.users(id, email, raw_user_meta_data) values ('{u}', '{name.lower()}@test.pk', '{{\"full_name\": \"{name}\"}}')")
    return u


def row(rows, name, key="name"):
    return next(r for r in rows if r[key] == name)


def rs(n):
    return n * 100


def main():
    K = make_user("Kamran")   # owner and Canteen Manager
    R = make_user("Riaz")
    B = make_user("Bilal")
    U = make_user("Usman")
    S = make_user("Saad")     # day member
    H = make_user("Hamza")    # day member
    O = make_user("Omar")     # owner of another hostel
    Z = make_user("Zara")     # not a member anywhere

    print("== access")
    cfg = as_user(Z, "select 1;")
    r = subprocess.run(PSQL + ["-c", "set role anon; select public.mw_me();"], capture_output=True, text=True)
    check("anon cannot call API", r.returncode != 0 and "permission denied" in r.stderr, r.stderr[:100])
    r = subprocess.run(PSQL + ["-c", "set role anon; select public.mw_app_config();"], capture_output=True, text=True)
    check("anon reads app config", r.returncode == 0 and "google_web_client_id" in r.stdout, r.stderr[:100])
    r = as_user(K, "select count(*) from messwise.places;")
    check("tables not readable", r.returncode != 0 and "permission denied" in r.stderr, r.stderr[:100])
    r = as_user(K, "select messwise.ensure_profile();")
    check("helpers not callable", r.returncode != 0 and "permission denied" in r.stderr, r.stderr[:100])
    me = call(K, "mw_me")
    check("profile from sign-in", me["profile"]["full_name"] == "Kamran" and me["places"] == [])

    print("== office or hostel and joining")
    expect_err("name too short", "SQL", call, K, "mw_place_create", {"name": "A"})
    pl = call(K, "mw_place_create", {"name": "Sunrise Office & Hostel", "address": "Blue Area, Islamabad", "kind": "hostel", "my_name": "Kamran"}, today="2026-11-01")
    P = pl["id"]
    code = pl["join_code"]
    check("owner is main manager", pl["me"]["role"] == "owner" and pl["me"]["is_main_manager"] and pl["can"]["manage"])
    check("join code", len(code) == 6)
    for who, t in ((R, "resident"), (B, "resident"), (U, "resident"), (S, "day"), (H, "day")):
        j = call(who, "mw_join", code.lower(), today="2026-11-01")
        check("join requested", j["status"] == "requested")
    reqs = call(K, "mw_join_requests", P)
    check("five requests", len(reqs) == 5)
    expect_err("member cannot approve", "NOT_FOUND", call, R, "mw_join_requests", P)
    types = {"Riaz": "resident", "Bilal": "resident", "Usman": "resident", "Saad": "day", "Hamza": "day"}
    for q in sorted(reqs, key=lambda x: ["Riaz", "Bilal", "Usman", "Saad", "Hamza"].index(x["full_name"])):
        call(K, "mw_join_decide", q["id"], {"approve": True, "type": types[q["full_name"]]}, today="2026-11-01")
    mem = call(K, "mw_members", P)
    ids = {m["name"]: m["id"] for m in mem}
    check("six members", len(mem) == 6)
    check("day member type", row(mem, "Saad")["type"] == "day" and not row(mem, "Saad")["on_kitchen"])
    again = call(R, "mw_join", code)
    check("join twice is fine", again["status"] == "member")
    check("member sees no join code", call(R, "mw_place_get", P)["join_code"] is None)
    expect_err("outsider sees nothing", "NOT_FOUND", call, Z, "mw_dashboard", P)

    print("== leave")
    call(U, "mw_leave_add", ids["Usman"], {"from_date": "2026-11-11", "to_date": "2026-11-20", "note": "Home"})
    expect_err("overlapping leave", "DUPLICATE", call, U, "mw_leave_add", ids["Usman"], {"from_date": "2026-11-15", "to_date": "2026-11-16"})
    expect_err("cannot mark others' leave", "FORBIDDEN", call, R, "mw_leave_add", ids["Usman"], {"from_date": "2026-11-21"})
    check("on leave mid-month", call(U, "mw_member_get", ids["Usman"], "2026-11-01", today="2026-11-15")["on_leave_today"])

    print("== purchases")
    expect_err("member cannot add purchases", "FORBIDDEN", call, R, "mw_purchase_save", {"place_id": P, "bill": "tea", "item": "Milk", "amount_paisa": rs(100), "bought_on": "2026-11-02"})
    expect_err("future date", "INVALID", call, K, "mw_purchase_save", {"place_id": P, "bill": "tea", "item": "Milk", "amount_paisa": rs(100), "bought_on": "2026-12-25"})
    for item, amt, bill in (("Milk", 3600, "tea"), ("Tea leaves", 900, "tea"), ("Sugar", 500, "tea"),
                            ("Cooking oil", 2400, "kitchen"), ("Gas cylinder", 3600, "kitchen"), ("Flour and rice", 3200, "kitchen"), ("Spices and vegetables", 1800, "kitchen")):
        e = call(K, "mw_purchase_save", {"place_id": P, "bill": bill, "item": item, "amount_paisa": rs(amt), "bought_on": "2026-11-03"})
    check("payer defaults to the manager", e["payers"][0]["name"] == "Kamran" and e["payers"][0]["amount_paisa"] == rs(1800))
    expect_err("payers must add up", "INVALID", call, K, "mw_purchase_save", {"place_id": P, "bill": "tea", "item": "Cups", "amount_paisa": rs(100), "bought_on": "2026-11-03",
                                                                              "payers": [{"member_id": ids["Riaz"], "amount_paisa": rs(50)}]})
    extra = call(K, "mw_purchase_save", {"place_id": P, "bill": "tea", "item": "Wrong entry", "amount_paisa": rs(999), "bought_on": "2026-11-04"})
    expect_err("edit needs reason", "INVALID", call, K, "mw_purchase_save", {"id": extra["id"], "place_id": P, "bill": "tea", "item": "Wrong entry", "amount_paisa": rs(990), "bought_on": "2026-11-04"})
    ed = call(K, "mw_purchase_save", {"id": extra["id"], "place_id": P, "bill": "tea", "item": "Wrong entry", "amount_paisa": rs(990), "bought_on": "2026-11-04", "reason": "Typo"})
    check("edit makes a new version", ed["version"] == 2)
    vd = call(K, "mw_purchase_void", extra["id"], "Entered by mistake")
    check("void keeps the entry", vd["status"] == "void")
    det = call(R, "mw_entry_get", "purchase", extra["id"])
    check("history visible to members", len(det["versions"]) == 2 and det["versions"][0]["reason"].startswith("Voided"))
    notes = call(R, "mw_notifications", None)
    check("edit notified", any(n["kind"] == "entry_edited" for n in notes) and any(n["kind"] == "entry_voided" for n in notes))

    print("== official tea count")
    day1 = [("Riaz", 15), ("Kamran", 20), ("Bilal", 17), ("Usman", 14), ("Saad", 15), ("Hamza", 10)]
    day2 = [("Riaz", 15), ("Kamran", 20), ("Bilal", 18), ("Usman", 15), ("Saad", 15), ("Hamza", 10)]
    rows1 = [{"subject": "member", "member_id": ids[n], "cups": c} for n, c in day1] + \
            [{"subject": "personal_guest", "member_id": ids["Bilal"], "cups": 4}, {"subject": "office_guest", "member_id": None, "cups": 12}]
    td = call(K, "mw_tea_set", P, "2026-11-05", rows1)
    check("day total", td["day_total"] == 107, td["day_total"])
    call(K, "mw_tea_set", P, "2026-11-25", [{"subject": "member", "member_id": ids[n], "cups": c} for n, c in day2])
    expect_err("member cannot count tea", "FORBIDDEN", call, R, "mw_tea_set", P, "2026-11-25", [])
    tr = call(K, "mw_tea_round", P, "2026-11-26", [ids["Riaz"], ids["Saad"]])
    check("tea round adds a cup", row(tr["rows"], "Riaz")["cups"] == 1 and tr["day_total"] == 2)
    call(K, "mw_tea_set", P, "2026-11-26", [{"subject": "member", "member_id": ids["Riaz"], "cups": 0}, {"subject": "member", "member_id": ids["Saad"], "cups": 0}])
    check("zero clears", call(K, "mw_tea_day", P, "2026-11-26")["day_total"] == 0)

    print("== my private tea tally")
    for _ in range(3):
        t = call(S, "mw_my_tea_add", P, 1, today="2026-11-25")
    check("tally counts", t["today_cups"] == 3)
    t = call(S, "mw_my_tea_undo", P, today="2026-11-25")
    check("undo", t["today_cups"] == 2)
    mine = call(S, "mw_my_tea", P, "2026-11-01")
    check("my tally beside official", mine["mine_total"] == 2 and mine["official_total"] == 30, json.dumps(mine)[:200])
    check("manager does not see it", call(K, "mw_my_tea", P, "2026-11-01")["mine_total"] == 0)

    print("== meals")
    lunch = call(K, "mw_meal_save", {"place_id": P, "day": "2026-11-15", "kind": "lunch", "title": "Biryani order", "amount_paisa": rs(3000),
                                     "people": [{"subject": "member", "member_id": ids[n]} for n in ("Riaz", "Kamran", "Bilal", "Saad", "Hamza")],
                                     "payers": [{"member_id": ids["Riaz"], "amount_paisa": rs(3000)}]})
    check("lunch split among eaters", all(s["share_paisa"] == rs(600) for s in lunch["shares"]) and len(lunch["shares"]) == 5, json.dumps(lunch["shares"]))
    dup = {"place_id": P, "day": "2026-11-16", "kind": "dinner", "amount_paisa": rs(100), "people": [{"subject": "member", "member_id": ids["Riaz"]}, {"subject": "member", "member_id": ids["Riaz"]}]}
    expect_err("member ticked twice", "DUPLICATE", call, K, "mw_meal_save", dup)
    # Guests on a meal: personal guest to host, office guest divided on everyone present.
    g = call(K, "mw_meal_save", {"place_id": P, "day": "2026-11-27", "kind": "dinner", "amount_paisa": rs(900),
                                 "people": [{"subject": "member", "member_id": ids["Riaz"]}, {"subject": "personal_guest", "member_id": ids["Riaz"]},
                                            {"subject": "office_guest", "member_id": None}]})
    gs = {s["name"]: s["share_paisa"] for s in g["shares"]}
    check("guest shares", gs.get("Riaz") == rs(650) and gs.get("Bilal") == rs(50) and sum(gs.values()) == rs(900), json.dumps(gs))
    call(K, "mw_meal_void", g["id"], "Test entry")

    print("== party")
    party = call(K, "mw_party_save", {"place_id": P, "day": "2026-11-30", "title": "Grand Dinner", "amount_paisa": rs(13500),
                                      "people": [{"subject": "member", "member_id": ids[n]} for n in ("Riaz", "Kamran", "Bilal", "Usman", "Saad", "Hamza")] +
                                                [{"subject": "personal_guest", "member_id": ids["Saad"], "heads": 1}, {"subject": "office_guest", "member_id": None, "heads": 2}]})
    ps = {s["name"]: s["share_paisa"] for s in party["shares"]}
    check("party per head", party["per_head_paisa"] == rs(1500) and party["heads"] == 9)
    check("party shares", ps == {"Riaz": rs(2000), "Kamran": rs(2000), "Bilal": rs(2000), "Usman": rs(2000), "Saad": rs(3500), "Hamza": rs(2000)}, json.dumps(ps))
    pb = {b["member_name"]: b for b in party["bills"]}
    check("party bills", pb["Saad"]["total_paisa"] == rs(3500) and pb["Kamran"]["total_paisa"] == rs(2000 - 13500) and pb["Riaz"]["status"] == "pending")

    print("== November, live")
    mon = call(R, "mw_month", P, "2026-11-01")
    tea = mon["tea"]
    check("tea totals", tea["total_paisa"] == rs(5000) and tea["cups_total"] == 200 and float(tea["rate"]) == 25.0, json.dumps(tea))
    check("kitchen per day", mon["kitchen"]["member_days"] == 110 and float(mon["kitchen"]["per_day"]) == 100.0, json.dumps(mon["kitchen"]))
    rows = {r["name"]: r for r in mon["rows"]}
    exp_tea = {"Riaz": 800, "Kamran": 1050, "Bilal": 1025, "Usman": 775, "Saad": 800, "Hamza": 550}
    exp_kit = {"Riaz": 3600, "Kamran": 3600, "Bilal": 3600, "Usman": 2000, "Saad": 600, "Hamza": 600}
    for n in exp_tea:
        check(f"tea bill {n}", rows[n]["tea_paisa"] == rs(exp_tea[n]), rows[n]["tea_paisa"])
        check(f"kitchen bill {n}", rows[n]["kitchen_shared_paisa"] + rows[n]["meals_paisa"] == rs(exp_kit[n]), rows[n]["kitchen_shared_paisa"] + rows[n]["meals_paisa"])
    check("riaz 30 cups", rows["Riaz"]["cups"] == 30 and rows["Bilal"]["guest_cups"] == 4)
    check("day members pay no shared kitchen", rows["Saad"]["kitchen_shared_paisa"] == 0 and rows["Saad"]["kitchen_days"] is None)
    dash = call(S, "mw_dashboard", P)
    check("dashboard so far", dash["my_so_far"]["tea_paisa"] == rs(800) and dash["place"]["managers"][0]["name"] == "Kamran")
    check("party pending visible to all", any(p["member_name"] == "Riaz" and p["kind"] == "party" for p in dash["pending"]))
    check("dashboard not admin for member", dash["admin"] is None)

    print("== bills")
    expect_err("member cannot bill", "FORBIDDEN", call, R, "mw_month_bill", P, "2026-11-01")
    expect_err("future month", "INVALID", call, K, "mw_month_bill", P, "2026-12-01")
    billed = call(K, "mw_month_bill", P, "2026-11-01")
    bills = {b["member_name"]: b for b in billed["bills"]}
    exp_pending = {"Riaz": 1400, "Bilal": 4625, "Usman": 2775, "Saad": 1400, "Hamza": 1150, "Kamran": -11350}
    for n, v in exp_pending.items():
        check(f"november bill {n}", bills[n]["total_paisa"] == rs(v), bills[n]["total_paisa"])
    check("pending adds up to manager's credit", sum(v for v in exp_pending.values() if v > 0) == -exp_pending["Kamran"])
    check("month locked", billed["status"] == "billed" and not billed["can_bill"])
    expect_err("billed month is locked", "STATE", call, K, "mw_purchase_save", {"place_id": P, "bill": "tea", "item": "Milk", "amount_paisa": rs(100), "bought_on": "2026-11-29"})
    expect_err("bill twice", "STATE", call, K, "mw_month_bill", P, "2026-11-01")
    check("bill notification", any(n["kind"] == "bill" and "1,400" in n["body"] for n in call(R, "mw_notifications", None)))

    print("== payments")
    rb = bills["Riaz"]["id"]
    expect_err("cannot pay someone else's bill", "FORBIDDEN", call, B, "mw_payment_send", {"place_id": P, "bill_id": rb, "amount_paisa": rs(1400), "method": "cash"})
    sent = call(R, "mw_payment_send", {"place_id": P, "bill_id": rb, "amount_paisa": rs(1400), "method": "jazzcash", "slip_path": f"{P}/slips/{R}/a.jpg"})
    check("slip sent", sent["status"] == "slip_sent")
    waiting = call(K, "mw_payments_waiting", P)
    check("manager sees slip", len(waiting) == 1 and waiting[0]["member_name"] == "Riaz" and waiting[0]["slip_path"])
    expect_err("member cannot mark paid", "FORBIDDEN", call, R, "mw_payment_decide", waiting[0]["id"], {"accept": True})
    done = call(K, "mw_payment_decide", waiting[0]["id"], {"accept": True})
    check("marked paid", done["status"] == "paid" and done["remaining_paisa"] == 0)
    bb = bills["Bilal"]["id"]
    s1 = call(B, "mw_payment_send", {"place_id": P, "bill_id": bb, "amount_paisa": rs(4625), "method": "bank"})
    pid = s1["payments"][0]["id"]
    expect_err("send back needs reason", "INVALID", call, K, "mw_payment_decide", pid, {"accept": False})
    back = call(K, "mw_payment_decide", pid, {"accept": False, "reason": "Slip unclear, please send again"})
    check("sent back", back["status"] == "pending")
    s2 = call(B, "mw_payment_send", {"place_id": P, "bill_id": bb, "amount_paisa": rs(4000), "method": "bank"})
    part = call(K, "mw_payment_decide", s2["payments"][0]["id"], {"accept": True})
    check("partly paid", part["status"] == "partly_paid" and part["remaining_paisa"] == rs(625))
    hb = bills["Hamza"]["id"]
    cash = call(K, "mw_payment_record", {"place_id": P, "bill_id": hb, "amount_paisa": rs(1150), "method": "cash", "note": "cash"})
    check("cash recorded", cash["status"] == "paid")
    check("member sees slip only for own bill", call(S, "mw_bill_get", rb)["payments"][0]["slip_path"] is None and call(R, "mw_bill_get", rb)["payments"][0]["slip_path"])

    print("== deputy and own bill")
    call(K, "mw_member_role", ids["Riaz"], {"role": "manager"})
    kb = pb["Kamran"]["id"]
    sb = bills["Saad"]["id"]
    expect_err("manager cannot mark own bill", "FORBIDDEN", call, R, "mw_payment_record", {"place_id": P, "bill_id": rb, "amount_paisa": rs(1), "method": "cash"})
    call(R, "mw_payment_record", {"place_id": P, "bill_id": sb, "amount_paisa": rs(1400), "method": "cash"})
    expect_err("pay out needs credit", "STATE", call, K, "mw_payment_record", {"place_id": P, "bill_id": sb, "direction": "out", "amount_paisa": rs(1), "method": "cash"})
    expect_err("only owner sets roles", "FORBIDDEN", call, R, "mw_member_role", ids["Bilal"], {"role": "manager"})

    print("== reminders and notices")
    rem = call(K, "mw_remind", P, {"member_ids": [], "note": "Before Friday"})
    check("reminders to everyone pending", rem["sent"] >= 4, json.dumps(rem))
    rem2 = call(K, "mw_remind", P, {"member_ids": [ids["Usman"]]})
    check("one reminder a day", rem2["sent"] == 0 and rem2["skipped"] == 1, json.dumps(rem2))
    un = call(U, "mw_notifications", None)
    check("reminder text", any(n["kind"] == "reminder" and "4,775" in n["body"] and "Kamran" in n["body"] for n in un), json.dumps([n["body"] for n in un])[:300])
    call(K, "mw_notice", P, "No tea tomorrow, milk not delivered")
    check("notice to all", any(n["kind"] == "notice" for n in call(H, "mw_notifications", None)))
    expect_err("member cannot send notice", "FORBIDDEN", call, S, "mw_notice", P, "Hello all")

    print("== questions with my tally")
    q = call(S, "mw_question_ask", {"place_id": P, "entity": "tea", "month": "2026-11-01", "text": "I counted fewer teas", "share_tally": True})
    qs = call(K, "mw_questions", P)
    check("manager sees question with tally", qs[0]["tally"]["mine_total"] == 2)
    check("other member does not", len(call(B, "mw_questions", P)) == 0)
    call(K, "mw_question_answer", q["id"], "Checked the sheet, 30 is right")
    check("answer notified", any(n["kind"] == "answer" for n in call(S, "mw_notifications", None)))

    print("== reopen, edit, bill again")
    expect_err("only owner reopens", "FORBIDDEN", call, R, "mw_month_reopen", P, "2026-11-01", "fix lunch")
    call(K, "mw_month_reopen", P, "2026-11-01", "Lunch bill was Rs 3,500")
    call(K, "mw_meal_save", {"id": lunch["id"], "place_id": P, "day": "2026-11-15", "kind": "lunch", "title": "Biryani order", "amount_paisa": rs(3500),
                             "people": [{"subject": "member", "member_id": ids[n]} for n in ("Riaz", "Kamran", "Bilal", "Saad", "Hamza")],
                             "payers": [{"member_id": ids["Riaz"], "amount_paisa": rs(3500)}], "reason": "Shop corrected the bill"})
    check("share change notified", any(n["kind"] == "share_changed" and "700" in n["body"] for n in call(H, "mw_notifications", None)))
    re = call(K, "mw_month_bill", P, "2026-11-01")
    rb2 = {b["member_name"]: b for b in re["bills"]}
    check("rebilled riaz", rb2["Riaz"]["total_paisa"] == rs(1000) and rb2["Riaz"]["status"] == "paid", json.dumps(rb2["Riaz"])[:200])
    check("rebilled kamran", rb2["Kamran"]["total_paisa"] == rs(1050 + 3700 - 16000))

    print("== rounding")
    a = sql("select messwise.alloc(500000, '[{\"k\":\"a\",\"w\":1,\"o\":1},{\"k\":\"b\",\"w\":1,\"o\":2},{\"k\":\"c\",\"w\":1,\"o\":3}]'::jsonb)")
    a = json.loads(a)
    check("thirds in whole rupees", a == {"a": 166700, "b": 166700, "c": 166600}, json.dumps(a))
    a = json.loads(sql("select messwise.alloc(500000, '[{\"k\":\"a\",\"w\":95,\"o\":1},{\"k\":\"b\",\"w\":60,\"o\":2},{\"k\":\"c\",\"w\":35,\"o\":3}]'::jsonb)"))
    check("190 cups add up", sum(a.values()) == 500000 and all(v % 100 == 0 for v in a.values()), json.dumps(a))
    a = json.loads(sql("select messwise.alloc(100050, '[{\"k\":\"a\",\"w\":1,\"o\":1},{\"k\":\"b\",\"w\":2,\"o\":2}]'::jsonb)"))
    check("paisa when not whole rupees", sum(a.values()) == 100050, json.dumps(a))

    print("== isolation and storage")
    other = call(O, "mw_place_create", {"name": "Al-Noor Girls Hostel", "my_name": "Omar"})
    expect_err("cannot read another hostel", "NOT_FOUND", call, R, "mw_dashboard", other["id"])
    expect_err("cannot pay into another hostel", "NOT_FOUND", call, O, "mw_payment_send", {"place_id": P, "bill_id": rb, "amount_paisa": rs(1), "method": "cash"})
    r = as_user(B, f"insert into storage.objects(bucket_id, name) values ('messwise', '{P}/slips/{B}/s.jpg');")
    check("member uploads own slip", r.returncode == 0, r.stderr[:120])
    r = as_user(S, f"insert into storage.objects(bucket_id, name) values ('messwise', '{P}/receipts/{S}/r.jpg');")
    check("member cannot upload receipts", r.returncode != 0)
    r = as_user(S, f"insert into storage.objects(bucket_id, name) values ('messwise', '{P}/slips/{B}/fake.jpg');")
    check("cannot upload into another's folder", r.returncode != 0)
    r = as_user(K, f"select count(*) from storage.objects where name = '{P}/slips/{B}/s.jpg';")
    check("manager reads slip", r.stdout.strip() == "1", r.stderr[:100])
    r = as_user(S, f"select count(*) from storage.objects where name = '{P}/slips/{B}/s.jpg';")
    check("other member cannot read slip", r.stdout.strip() == "0")
    r = as_user(O, f"select count(*) from storage.objects where bucket_id = 'messwise';")
    check("outsider reads nothing", r.stdout.strip() == "0")

    print("== private expense book")
    call(R, "mw_personal_save", {"amount_paisa": rs(4100), "category": "Food outside", "day": "2026-11-20"})
    pe = call(R, "mw_personal", "2026-11-01")
    check("personal total", pe["personal_total_paisa"] == rs(4100) and pe["by_category"][0]["category"] == "Food outside")
    check("mess bills alongside", pe["mess"] and pe["mess"][0]["charges_paisa"] > 0)
    check("private to its owner", call(K, "mw_personal", "2026-11-01")["personal_total_paisa"] == 0)

    print("== leaving and deleting")
    call(K, "mw_payment_record", {"place_id": P, "bill_id": bills["Usman"]["id"], "amount_paisa": rs(2775), "method": "cash"})
    expect_err("cannot leave with pending", "STATE", call, K, "mw_member_leave_place", ids["Usman"], {"left_on": "2026-11-30"})
    lp = call(K, "mw_member_leave_place", ids["Usman"], {"left_on": "2026-11-30", "write_off_reason": "Paid in cash to owner"})
    check("left with write-off", lp["status"] == "left")
    expect_err("owner cannot delete with members", "STATE", call, K, "mw_account_delete")
    call(H, "mw_account_delete")
    gone = sql(f"select count(*) from auth.users where id = '{H}'")
    kept = sql(f"select display_name from messwise.members where id = '{ids['Hamza']}'")
    check("account deleted, records kept", gone == "0" and kept == "Former member")
    hist = call(K, "mw_activity", P, None)
    check("activity log", len(hist) > 20 and hist[0]["actor_name"])

    print(f"\n{PASS} passed, {len(FAIL)} failed")
    for f in FAIL:
        print(" -", f)
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
