# Builds the Play Store phone screenshots from the app's real layout, colours and texts (sample data).
import pathlib
from playwright.sync_api import sync_playwright

P = {  # Material icon paths (24px)
 "home":"M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z",
 "bell":"M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z",
 "swap":"M6.99 11L3 15l3.99 4v-3H14v-2H6.99v-3zM21 9l-3.99-4v3H10v2h7.01v3L21 9z",
 "receipt":"M18 17H6v-2h12v2zm0-4H6v-2h12v2zm0-4H6V7h12v2zM3 22l1.5-1.5L6 22l1.5-1.5L9 22l1.5-1.5L12 22l1.5-1.5L15 22l1.5-1.5L18 22l1.5-1.5L21 22V2l-1.5 1.5L18 2l-1.5 1.5L15 2l-1.5 1.5L12 2l-1.5 1.5L9 2 7.5 3.5 6 2 4.5 3.5 3 2v20z",
 "pay":"M20 4H4c-1.11 0-1.99.89-1.99 2L2 18c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V6c0-1.11-.89-2-2-2zm0 14H4v-6h16v6zm0-10H4V6h16v2z",
 "book":"M18 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 4h5v8l-2.5-1.5L6 12V4z",
 "menu":"M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z",
 "back":"M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
 "cup":"M20 3H4v10c0 2.21 1.79 4 4 4h6c2.21 0 4-1.79 4-4v-3h2c1.11 0 2-.89 2-2V5c0-1.11-.89-2-2-2zm0 5h-2V5h2v3zM4 19h16v2H4z",
 "add":"M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
 "minus":"M19 13H5v-2h14v2z",
 "undo":"M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z",
 "doc":"M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z",
 "table":"M3 3v18h18V3H3zm8 16H5v-6h6v6zm0-8H5V5h6v6zm8 8h-6v-6h6v6zm0-8h-6V5h6v6z",
 "download":"M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z",
 "lock":"M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z",
 "cal":"M19 3h-1V1h-2v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5V8h14v11z",
 "left":"M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z",
 "right":"M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z",
 "basket":"M17.21 9l-4.38-6.56c-.19-.28-.51-.42-.83-.42-.32 0-.64.14-.83.43L6.79 9H2c-.55 0-1 .45-1 1 0 .09.01.18.04.27l2.54 9.27c.23.84 1 1.46 1.92 1.46h13c.92 0 1.69-.62 1.93-1.46l2.54-9.27L23 10c0-.55-.45-1-1-1h-4.79zM9 9l3-4.4L15 9H9zm3 8c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z",
 "meal":"M11 9H9V2H7v7H5V2H3v7c0 2.12 1.66 3.84 3.75 3.97V22h2.5v-9.03C11.34 12.84 13 11.12 13 9V2h-2v7zm5-3v8h2.5v8H21V2c-2.76 0-5 2.24-5 4z",
 "star":"M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z",
}
def ic(name, size=24, color="currentColor", style=""):
    return f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" style="flex:none;{style}"><path fill="{color}" d="{P[name]}"/></svg>'

CSS = """
*{box-sizing:border-box;margin:0;padding:0}
body{width:360px;height:640px;overflow:hidden;font-family:Roboto,'Noto Sans',sans-serif;color:#2A1D14;-webkit-font-smoothing:antialiased}
.page{position:relative;width:360px;height:640px;overflow:hidden;background:radial-gradient(circle at 85% 8%,rgba(242,169,0,.35),rgba(242,169,0,0) 38%),linear-gradient(165deg,#5a2e54 0%,#4A2545 45%,#2e1129 100%)}
.cap{position:absolute;left:24px;right:24px;top:30px;color:#fff;text-align:center}
.cap h1{font-size:24px;line-height:29px;font-weight:700;letter-spacing:-.2px}
.cap p{margin-top:8px;font-size:13.5px;line-height:18px;color:#FFE2A3}
.phone{position:absolute;left:30px;top:140px;width:300px;border-radius:34px;background:#141014;padding:8px;box-shadow:0 18px 40px rgba(0,0,0,.45)}
.screenwrap{width:284px;height:540px;border-radius:27px;overflow:hidden;position:relative;background:#FFF8EC}
.screen{width:360px;height:684px;transform:scale(.78889);transform-origin:0 0;background:#FFF8EC;position:relative}
.status{height:28px;display:flex;align-items:center;justify-content:space-between;padding:0 18px;font-size:13px;font-weight:500;color:#2A1D14}
.status .r{display:flex;gap:5px;align-items:center}
.top{height:64px;display:flex;align-items:center;padding:0 4px 0 16px;gap:4px}
.top .t{flex:1;font-size:22px;font-weight:400;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.top .ib{width:48px;height:48px;display:flex;align-items:center;justify-content:center;color:#2A1D14;position:relative}
.badge{position:absolute;top:8px;right:8px;background:#B42318;color:#fff;font-size:10px;font-weight:500;border-radius:8px;min-width:16px;height:16px;display:flex;align-items:center;justify-content:center;padding:0 4px}
.content{padding:8px 16px;display:flex;flex-direction:column;gap:12px}
.card{background:#fff;border-radius:16px;box-shadow:0 1px 2px rgba(0,0,0,.12),0 1px 3px rgba(0,0,0,.06);padding:16px;display:flex;flex-direction:column;gap:8px}
.ct{font-size:16px;font-weight:600;line-height:24px}
.row{display:flex;align-items:center}
.grow{flex:1}
.lab{font-size:14px;color:#6B5B4E;flex:1}
.val{font-size:14px;font-weight:500;text-align:right}
.val.strong{font-size:16px;font-weight:700}
.small{font-size:12px;color:#6B5B4E;line-height:16px}
.hint{background:rgba(255,235,194,.6);border-radius:12px;padding:12px;font-size:12px;line-height:16px;color:#3D2A00}
.big{font-size:22px;font-weight:700;line-height:28px}
.btn{height:40px;border-radius:20px;background:#F2A900;color:#2A1D14;font-size:14px;font-weight:600;display:flex;align-items:center;justify-content:center;gap:8px}
.btn2{height:40px;border-radius:20px;border:1px solid #B7A797;color:#4A2545;font-size:14px;font-weight:600;display:flex;align-items:center;justify-content:center;gap:8px;padding:0 16px}
.tonal{height:36px;border-radius:18px;background:#FFEBC2;color:#3D2A00;font-size:14px;font-weight:500;display:inline-flex;align-items:center;gap:6px;padding:0 16px 0 12px}
.chip{display:inline-block;border-radius:50px;padding:3px 10px;font-size:12px;font-weight:600;line-height:16px}
.c-owe{color:#B42318;background:#FBE3E0}.c-good{color:#1E7B47;background:#DDF1E4}.c-info{color:#5B6780;background:#E6E9F0}.c-part{color:#8A5300;background:#FFEFC7}
.div{height:1px;background:#E9DFD3}
.nav{position:absolute;left:0;right:0;bottom:0;height:80px;background:#FBF3E7;display:flex;justify-content:space-around;padding-top:12px}
.ni{display:flex;flex-direction:column;align-items:center;gap:4px;width:64px;font-size:12px;font-weight:500;color:#6B5B4E}
.ni .pill{width:64px;height:32px;border-radius:16px;display:flex;align-items:center;justify-content:center}
.ni.on{color:#2A1D14}.ni.on .pill{background:#FFEBC2}
.step{display:flex;align-items:center;gap:6px}
.sb{width:36px;height:36px;border-radius:18px;border:1px solid #B7A797;display:flex;align-items:center;justify-content:center;color:#4A2545}
.sn{min-width:28px;text-align:center;font-size:18px;font-weight:700}
.field{border:1px solid #B7A797;border-radius:4px;height:56px;padding:0 16px;display:flex;align-items:center;position:relative;font-size:16px;background:#FFF8EC}
.field .fl{position:absolute;top:-8px;left:12px;background:#FFF8EC;padding:0 4px;font-size:12px;color:#6B5B4E}
"""

def status():
    return f'''<div class="status"><span>10:24</span><span class="r">
<svg width="16" height="12" viewBox="0 0 16 12"><path d="M8 11.5L0 3.2C2.2 1.3 5 0 8 0s5.8 1.3 8 3.2z" fill="#2A1D14"/></svg>
<svg width="14" height="14" viewBox="0 0 14 14"><path d="M0 14L14 0v14z" fill="#2A1D14"/></svg>
<svg width="22" height="12" viewBox="0 0 22 12"><rect x=".5" y=".5" width="18" height="11" rx="2.5" fill="none" stroke="#2A1D14"/><rect x="2" y="2" width="13" height="8" rx="1.2" fill="#2A1D14"/><rect x="19.5" y="4" width="2" height="4" rx="1" fill="#2A1D14"/></svg></span></div>'''

def nav(on):
    items=[("home","Home","home"),("receipt","Entries","entries"),("pay","Bills","bills"),("book","My book","book"),("menu","More","more")]
    h=''.join(f'<div class="ni {"on" if k==on else ""}"><div class="pill">{ic(i,24)}</div>{l}</div>' for i,l,k in items)
    return f'<div class="nav">{h}</div>'

def topbar(title, back=False, place=True):
    left = f'<div class="ib" style="margin-left:-12px">{ic("back")}</div>' if back else ''
    right = (f'<div class="ib">{ic("swap")}</div><div class="ib">{ic("bell")}<span class="badge">2</span></div>') if place else ''
    return f'<div class="top">{left}<div class="t">{title}</div>{right}</div>'

def info(l, v, strong=False, color=None):
    st = f' style="color:{color}"' if color else ''
    return f'<div class="row"><div class="lab">{l}</div><div class="val{" strong" if strong else ""}"{st}>{v}</div></div>'

def page(h1, sub, screen):
    return f'''<!doctype html><html><head><meta charset="utf-8"><style>{CSS}</style></head><body><div class="page">
<div class="cap"><h1>{h1}</h1><p>{sub}</p></div>
<div class="phone"><div class="screenwrap"><div class="screen">{status()}{screen}</div></div></div></div></body></html>'''

# ---------- sample data (one hostel, November 2026, figures so far) ----------
RATE = 14100/420  # tea spent ÷ cups made
def rs(x):
    p = round(x*100); r, q = divmod(p, 100)
    g = f"{r:,}"
    return f"Rs {g}" if q == 0 else f"Rs {g}.{q:02d}"
people = [("Ali Raza",82,25,4,1400),("Bilal Ahmed",64,25,2,700),("Hamza Khan",71,22,0,0),("Usman Tariq",58,28,3,1050),("Saad Malik",77,25,0,0),("Fahad Iqbal",68,25,0,0)]
assert sum(p[1] for p in people)==420 and sum(p[2] for p in people)==150

# 1. Home
ali = people[0]; tea_ali = ali[1]*RATE; kit_ali = ali[2]*240; total_ali = tea_ali+kit_ali+ali[4]
home = topbar("Boys Hostel Block C") + f'''<div class="content">
<div class="card"><div class="row"><div class="grow">
 <div class="ct">My tea today</div>
 <div style="font-size:24px;font-weight:700;color:#4A2545;line-height:32px">3 cups</div>
 <div class="small">Manager's count today: 3</div></div>
 <div class="btn" style="height:64px;border-radius:16px;padding:0 18px;font-size:18px;font-weight:700">{ic("cup",24,"#2A1D14")}Add Tea</div></div>
 <div style="display:flex;align-items:center;gap:6px;color:#4A2545;font-size:14px;font-weight:500;padding:6px 4px">{ic("undo",18,"#4A2545")}Undo last cup</div>
 <div class="hint">Only you see these marks. Use them to check the manager's tea count at month end.</div></div>
<div class="card"><div class="ct">My bills</div>
 <div style="font-size:24px;font-weight:700;color:#B42318;line-height:32px">You owe Rs 9,480</div>
 <div class="row" style="padding:8px 0"><div class="grow"><div style="font-size:16px;font-weight:600">October 2026 bill</div><div class="small">Bill Rs 9,480</div></div>
 <div style="text-align:right"><div style="font-weight:700;color:#B42318;font-size:14px;margin-bottom:3px">Rs 9,480</div><span class="chip c-owe">Pending</span></div></div>
 <div class="btn">{ic("pay",18,"#2A1D14")}Pay now</div></div>
<div class="card"><div class="ct">November 2026 so far</div>
 <div class="row" style="gap:12px"><div class="grow"><div class="big">{rs(RATE)}</div><div class="small">Tea rate per cup</div></div><div class="grow"><div class="big">420</div><div class="small">Cups made</div></div></div>
 {info("Tea expenses","Rs 14,100")}{info("Kitchen supplies","Rs 36,000")}{info("Meal bills","Rs 3,150")}
 <div class="small" style="margin-top:4px">My share so far</div>
 {info("Tea, 82 cups", rs(tea_ali))}{info("Kitchen share", rs(kit_ali))}{info("4 meals","Rs 1,400")}{info("Total so far", rs(total_ali), True)}
</div></div>'''

# 2. Month sheet
rows = ''
for i,(n,c,d,mc,mp) in enumerate(people):
    t = c*RATE; k = d*240; tot = t+k+mp
    parts = [f"Tea {c} cups {rs(t)}", f"Kitchen {d} days {rs(k)}"] + ([f"Meals {mc} {rs(mp)}"] if mp else [])
    rows += ('<div class="div"></div>' if i else '') + f'<div style="padding:6px 0"><div class="row"><div class="grow" style="font-weight:600;font-size:14px">{n}</div><div style="font-weight:700;font-size:14px">{rs(tot)}</div></div><div class="small">{" · ".join(parts)}</div></div>'
month = topbar("November 2026", back=True, place=False) + f'''<div class="content">
<div class="row"><div class="grow" style="font-size:16px;font-weight:500">Month open</div></div>
<div class="hint">Figures so far, up to 26 Nov 2026.</div>
<div class="card"><div class="ct">Tea Bill</div>
 <div class="row" style="gap:12px"><div class="grow"><div class="big">Rs 14,100</div><div class="small">Tea expenses</div></div><div class="grow"><div class="big">420</div><div class="small">Cups made</div></div></div>
 {info("Tea rate per cup", rs(RATE), True)}
 <div class="hint">Rs 14,100 ÷ 420 cups</div></div>
<div class="card"><div class="ct">Kitchen Bill</div>
 {info("Shared supplies","Rs 36,000")}{info("Days present (all)","150")}{info("Per person per day","Rs 240")}{info("Meal bills","Rs 3,150")}</div>
<div class="card"><div class="ct">Everyone</div>{rows}</div>
</div>'''

# 3. Count tea
tea_rows=''
today=[("Ali Raza",3,0),("Bilal Ahmed",2,1),("Hamza Khan",3,0),("Usman Tariq",2,0),("Saad Malik",3,2),("Fahad Iqbal",2,0)]
def stepper(v):
    return f'<div class="step"><div class="sb">{ic("minus",18,"#4A2545")}</div><div class="sn">{v}</div><div class="sb">{ic("add",18,"#4A2545")}</div></div>'
for i,(n,c,g) in enumerate(today):
    tea_rows += ('<div class="div"></div>' if i else '') + f'''<div style="padding:6px 0;display:flex;flex-direction:column;gap:6px">
 <div class="row"><div class="grow" style="font-size:16px;font-weight:600">{n}</div>{stepper(c)}</div>
 <div class="row"><div class="small grow">Their guests</div>{stepper(g)}</div></div>'''
counted = sum(c+g for _,c,g in today)+2
teac = topbar("Count tea", back=True, place=False) + f'''<div class="content">
<div class="field"><span class="fl">Date</span>{ic("cal",20,"#6B5B4E","margin-right:12px")}26 Nov 2026</div>
<div class="card"><div class="row" style="gap:12px"><div class="grow"><div class="big">{counted}</div><div class="small">Cups this day</div></div><div class="grow"><div class="big">420</div><div class="small">Cups this month</div></div></div>
 {info("Rate per cup so far", rs(RATE))}</div>
<div class="btn2">{ic("cup",18,"#4A2545")}One cup for everyone present</div>
<div class="card"><div class="ct">Members</div>{tea_rows}</div>
</div>'''

# 4. Reports
reps=[("Total Tea Expenses","Every tea purchase, the total, cups made and the rate per cup"),("Teas per Person","Cups for each person and their guests, and cups day by day"),("Tea Bills","Each person's cups × rate"),("Total Kitchen Expenses","Everything other than tea: supplies, meals and parties"),("Kitchen Bill on All","How the Kitchen Bill is divided, with everyone's share"),("Kitchen Bill per Person","A section for each person with their full month bill")]
rcards=''.join(f'''<div class="card"><div class="ct">{t}</div><div class="small">{d}</div>
<div class="row" style="gap:8px"><div class="btn2" style="height:36px">{ic("doc",18,"#4A2545")}PDF</div><div class="btn2" style="height:36px">{ic("table",18,"#4A2545")}Excel</div></div></div>''' for t,d in reps)
rep = topbar("Reports", back=True, place=False) + f'''<div class="content">
<div class="row" style="justify-content:space-between"><div class="sb" style="border:none">{ic("left",24,"#2A1D14")}</div><div style="font-size:16px;font-weight:600">November 2026</div><div class="sb" style="border:none">{ic("right",24,"#2A1D14")}</div></div>
<div class="row" style="gap:8px"><span class="chip" style="background:#FFEBC2;color:#3D2A00;font-size:14px;padding:6px 14px;font-weight:500">✓ Share</span><span class="chip" style="border:1px solid #B7A797;color:#6B5B4E;font-size:14px;padding:5px 14px;font-weight:500">Save to Downloads</span></div>
{rcards}</div>'''

shots = [
 ("01-add-tea", "Add Tea in one tap", "Keep your own private count and see what you owe", home),
 ("02-month-bill", "Bills worked out for you", "Tea by cups, kitchen by days present", month),
 ("03-count-tea", "Count everyone's tea", "Guests go to their host, office guests are shared", teac),
 ("04-reports", "Six reports in PDF and Excel", "Made on your phone, ready to share with everyone", rep),
]
out = pathlib.Path(__file__).parent
with sync_playwright() as p:
    b = p.chromium.launch(executable_path="/usr/bin/google-chrome", args=["--no-sandbox"])
    pg = b.new_page(viewport={"width":360,"height":640}, device_scale_factor=3)
    for name,h1,sub,scr in shots:
        html = page(h1,sub,scr)
        (out/f"{name}.html").write_text(html)
        pg.set_content(html); pg.wait_for_timeout(300)
        pg.screenshot(path=str(out/f"{name}.png"))
    b.close()
print("done")
