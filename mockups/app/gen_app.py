import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
icons = root.parent / "assets" / "icons"


def icon(name, size=24, sw=2, color="currentColor"):
    s = (icons / f"{name}.svg").read_text()
    s = re.sub(r'width="24"', f'width="{size}"', s)
    s = re.sub(r'height="24"', f'height="{size}"', s)
    s = s.replace('stroke-width="2"', f'stroke-width="{sw}"').replace("currentColor", color)
    return re.sub(r'class="[^"]*"', "", s)


CSS = """
@font-face{font-family:M;src:url(../assets/fonts/Montserrat.ttf)}
*{box-sizing:border-box;margin:0;padding:0}
body{width:412px;height:915px;overflow:hidden;background:#fff;color:#111;font-family:M;font-weight:500;display:flex;flex-direction:column}
.sys{height:36px;display:flex;justify-content:space-between;align-items:center;padding:0 22px;font-size:14px;font-weight:600}
.bar{height:64px;display:flex;align-items:center;gap:12px;padding:0 20px;font-size:26px;font-weight:700}
.bar .act{margin-left:auto;display:flex;gap:16px}
.main{flex:1;overflow:hidden;padding:4px 16px 0;display:flex;flex-direction:column;gap:14px}
.card{border-radius:24px;background:#f3f3f3;padding:18px 18px}
.card.dark{background:#111;color:#fff}
.row{display:flex;align-items:center;gap:12px}
.sp{margin-left:auto}
.h{font-size:17px;font-weight:700}
.s{font-size:13px;color:#666;font-weight:500}
.dark .s{color:#bbb}
.big{font-size:30px;font-weight:700;letter-spacing:-.5px}
.pill{display:inline-flex;align-items:center;gap:6px;border-radius:999px;padding:5px 11px;font-size:12px;font-weight:700;background:#fff;color:#111}
.pill.on{background:#111;color:#fff}
.dark .pill{background:#2a2a2a;color:#fff}
.sec{font-size:12px;font-weight:700;letter-spacing:1px;text-transform:uppercase;color:#666;padding:6px 4px 0}
.list{border-radius:24px;background:#f3f3f3;overflow:hidden}
.li{display:flex;align-items:center;gap:14px;padding:15px 18px;border-top:1px solid #e2e2e2;font-size:15px;font-weight:600}
.li:first-child{border-top:none}
.li > svg:last-child{margin-left:auto;flex:none}
.li .t{display:flex;flex-direction:column;gap:3px;min-width:0}
.li .t b{font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.chk{width:26px;height:26px;border-radius:8px;border:2.5px solid #111;flex:none;display:flex;align-items:center;justify-content:center}
.chk.on{background:#111}
.radio{width:22px;height:22px;border-radius:50%;border:2.5px solid #111;flex:none}
.radio.on{border-width:7px}
.sw{width:50px;height:30px;border-radius:999px;background:#111;position:relative;flex:none}
.sw:after{content:'';position:absolute;top:4px;right:4px;width:22px;height:22px;border-radius:50%;background:#fff}
.sw.off{background:#ccc}.sw.off:after{right:auto;left:4px}
.btn{height:48px;border-radius:999px;border:2px solid #111;display:flex;align-items:center;justify-content:center;gap:8px;font-size:15px;font-weight:700}
.btn.on{background:#111;color:#fff}
.nav{height:80px;border-top:1px solid #e5e5e5;display:flex;justify-content:space-around;align-items:center;padding-bottom:6px}
.nav div{display:flex;flex-direction:column;align-items:center;gap:4px;font-size:12px;font-weight:700;color:#555}
.nav i{display:flex;width:64px;height:32px;border-radius:999px;align-items:center;justify-content:center}
.nav .on{color:#111}.nav .on i{background:#111}
.dot{width:8px;height:8px;border-radius:50%;background:#111;display:inline-block}
.tl{display:flex;gap:12px;padding:9px 12px;border-radius:14px;font-size:15px;line-height:1.45}
.tl .at{flex:none;width:62px;font-size:13px;font-weight:700;color:#666;padding-top:2px;display:flex;gap:3px;align-items:center;height:20px}.tl .at svg{flex:none}
.tl.near{background:#f3f3f3;border-radius:0}
.tl.now{background:#111;color:#fff;font-weight:600}.tl.now .at{color:#bbb}
.player{border-top:1px solid #e5e5e5;padding:12px 20px 22px;display:flex;flex-direction:column;gap:6px;background:#fff}
.seek{position:relative;height:22px}
.seek .track{position:absolute;left:0;right:0;top:13px;height:4px;border-radius:2px;background:#ddd}
.seek .done{position:absolute;left:0;top:13px;height:4px;border-radius:2px;background:#111}
.seek .knob{position:absolute;top:8px;width:14px;height:14px;margin-left:-7px;border-radius:50%;background:#111}
.seek .mk{position:absolute;top:-6px;margin-left:-7px;display:flex}
.ctl{display:flex;align-items:center;justify-content:center;gap:34px}
.segm{display:inline-flex;border-radius:999px;background:#f3f3f3;padding:3px}
.segm span{border-radius:999px;padding:5px 12px;font-size:13px;font-weight:700;color:#555}
.segm .on{background:#111;color:#fff}
.segm.big span{padding:9px 22px;font-size:14px}
.who{display:inline-block;margin-right:6px;padding:1px 8px;border-radius:999px;background:#e2e2e2;font-size:11px;font-weight:700;vertical-align:1px}
.near .who{background:#e2e2e2}.now .who{background:#2a2a2a;color:#fff}
.tl i{font-style:normal;color:#999;letter-spacing:1px}.now i{color:#888}
.opt{display:inline-flex;align-items:center;gap:5px;border-radius:999px;padding:6px 13px;font-size:13px;font-weight:700;background:#f3f3f3}
.opt.on{background:#111;color:#fff}
.chip{display:inline-flex;align-items:center;border-radius:999px;padding:9px 18px;font-size:14px;font-weight:700;background:#f3f3f3}
.chip.on{background:#111;color:#fff}
.fld{border-radius:24px;background:#f3f3f3;padding:18px}
.play{width:60px;height:60px;border-radius:50%;background:#111;display:flex;align-items:center;justify-content:center}
.bar2{height:6px;border-radius:999px;background:#ddd;overflow:hidden}.bar2 div{height:100%;background:#111}
p.q{font-size:15px;line-height:1.5;font-weight:500}
"""

SYS = '<div class=sys><span>10:42</span><span style="display:flex;gap:6px;align-items:center">' + icon("wifi", 16) + icon("battery-4", 18) + "</span></div>"
TABS = [("Hub", "home"), ("Transcripts", "file-text"), ("Snap", "camera"), ("To-do", "list-check"), ("Photos", "photo")]


def nav(on):
    cells = []
    for n, i in TABS:
        if n == "Snap":
            cells.append(f'<div style="position:relative"><span style="position:absolute;top:-34px;left:50%;transform:translateX(-50%);width:64px;height:64px;border-radius:50%;background:#111;display:flex;align-items:center;justify-content:center;box-shadow:0 0 0 6px #fff">{icon("camera", 30, 2.2, "#fff")}</span><span style="margin-top:34px">Snap</span></div>')
        else:
            cells.append(f'<div class={"on" if n == on else ""}><i>{icon(i, 22, 2, "#fff" if n == on else "#555")}</i>{n}</div>')
    return "<div class=nav>" + "".join(cells) + "</div>"


def page(name, title, body, tab=None, back=False, act="", foot=""):
    head = (icon("chevron-left", 26, 2.4) if back else "") + title + (f"<span class=act>{act}</span>" if act else "")
    html = f"<!doctype html><meta charset=utf-8><style>{CSS}</style><body>{SYS}<div class=bar>{head}</div><div class=main>{body}</div>{foot}{nav(tab) if tab else ''}</body>"
    (root / f"{name}.html").write_text(html)


CHECK = icon("check", 18, 3, "#fff")
STAR = icon("star-filled", 13)
CARD = "background:#f3f3f3"


def pill(text, style=CARD):
    return f'<span class=pill style="{style}">{text}</span>'


def li(title, sub="", lead="", trail=""):
    sub = f"<span class=s>{sub}</span>" if sub else ""
    return f'<div class=li>{lead}<span class=t><b>{title}</b>{sub}</span>{trail}</div>'


def lst(*rows):
    return "<div class=list>" + "".join(rows) + "</div>"


def sec(text):
    return f"<div class=sec>{text}</div>"


def seg(options, on, big=True):
    return f'<span class="segm{" big" if big else ""}">' + "".join(f'<span class="{"on" if i == on else ""}">{o}</span>' for i, o in enumerate(options)) + "</span>"


def chips(options, on):
    return '<div class=row style="gap:8px">' + "".join(f'<span class="chip{" on" if i == on else ""}">{o}</span>' for i, o in enumerate(options)) + "</div>"


def radio(on):
    return f'<span class="radio{" on" if on else ""}"></span>'


def chk(on):
    return f'<span class="chk{" on" if on else ""}">{CHECK if on else ""}</span>'


DEFAULT = '<span class="pill sp" style="background:#fff">Default</span>'
CHEV = icon("chevron-right", 20)
SHEET = '<div style="position:absolute;left:0;top:0;width:412px;height:915px;background:rgba(0,0,0,.45)"></div><div style="position:absolute;left:0;bottom:0;width:412px;background:#fff;border-radius:28px 28px 0 0;padding:14px 16px 28px;display:flex;flex-direction:column;gap:14px"><div style="width:40px;height:5px;border-radius:3px;background:#ccc;align-self:center"></div>'

TILES = [("device-mobile", "Sticky", "Wi-Fi, clock, sounds"), ("cloud", "Weather", "Lisbon · 18°"), ("map-pin", "Places", "Home, Office"),
         ("calendar", "Calendars", "4 calendars"), ("heart-rate-monitor", "Health", "OK · 34 °C"), ("photo", "Photo to Sticky", "Pick an image"),
         ("settings", "App settings", "Models, transcribe, hubs")]


def sticky_card(extra=""):
    return f"""<div class="card dark" style="padding:16px 18px"><div class=row>{icon("device-mobile", 26, 2, "#fff")}<div><div class=h>Sticky · desk</div><div class=s>Connected over Bluetooth</div></div>
<span class=sp style="display:flex;align-items:center;gap:6px">{extra}{icon("chevron-down", 22, 2.4, "#fff")}</span></div>
<div class=row style="margin-top:14px;gap:8px"><span class=pill><span class=dot style="background:#fff"></span>REC</span><span class=pill>93%</span><span class=pill>2 h 14 today</span><span class=pill>0 waiting</span></div></div>"""


def tiles(items):
    return '<div style="display:grid;grid-template-columns:1fr 1fr;gap:12px">' + "".join(
        f'<div class=card style="padding:16px;display:flex;flex-direction:column;gap:10px">{icon(i, 28)}<div><div class=h style="font-size:16px">{n}</div><div class=s>{d}</div></div></div>'
        for i, n, d in items) + "</div>"


page("hub", "Hub", sticky_card() + tiles(TILES), "Hub")

page("device-picker", "Hub", sticky_card() + tiles(TILES), "Hub", foot=SHEET + f"""
<div class=h style="font-size:20px;padding:0 4px">Choose a Sticky</div>
{lst(li("Sticky · desk", "Connected over Bluetooth · 93%", radio(True)), li("Sticky · kitchen", "Paired", radio(False)))}
<div class=btn>{icon("plus", 20)}Pair a new Sticky</div></div>""")

STICKY_ROWS = f"""{sec("Display")}
{lst(li("Home screen", "Apps", icon("home", 22), CHEV), li("Clock", "Dial · 24 h", icon("clock", 22), CHEV), li("Weather", "Sketch · Lisbon", icon("cloud", 22), CHEV))}
{sec("Sticky")}
{lst(li("Sounds on the Sticky", "Button and alert sounds", icon("bell", 22), '<span class="sw sp"></span>'),
     li("Wi-Fi networks", "HomeWiFi and 1 more", icon("wifi", 22), CHEV), li("Health", "OK · 34 °C", icon("heart-rate-monitor", 22), CHEV))}"""

page("sticky", "Sticky settings", f"""
<div class="card dark" style="padding:18px 22px"><div class=h>Sticky · desk</div><div class=s style="margin-top:4px">Connected over Bluetooth · firmware 0.14.3</div></div>
{STICKY_ROWS}
{sec("Device")}
{lst(li("Firmware 0.14.3", "Latest 0.14.4 · checked today 10:42", "", '<span class="pill sp on">Update now</span>'), li("Link", "Bluetooth · Wi-Fi for large backlogs"))}
<div class=btn>Forget this Sticky</div>""", back=True)

DIGIT = 'flex:1;height:58px;border-radius:14px;background:#2a2a2a;display:flex;align-items:center;justify-content:center;font-size:28px;font-weight:700'
page("sticky-pair", "Sticky settings", f"""
<div class="card dark" style="padding:22px"><div class=h>Pair the Sticky</div><div class=s style="margin-top:4px">Type the code that the Sticky shows</div>
<div class=row style="gap:8px;margin-top:16px">""" + "".join(f'<div style="{DIGIT}">{d}</div>' for d in "4829") + f"""<div style="{DIGIT};background:none;border:2px solid #fff"></div><div style="{DIGIT}"></div></div></div>
{STICKY_ROWS}""", back=True)

page("sticky-wifi", "Wi-Fi networks", f"""
{sec("On the Sticky")}
{lst(li("HomeWiFi", "Connected · −58 dBm", icon("wifi", 22), '<span class=sp style="font-size:14px;font-weight:700">Forget</span>'),
     li("Office-5G", "Saved", icon("wifi", 22), '<span class=sp style="font-size:14px;font-weight:700">Forget</span>'))}
{sec("Add a network")}
{lst(li("Phone hotspot", "", icon("wifi", 22), icon("lock", 18)), li("Cafe-Guest", "", icon("wifi-2", 22)),
     li("Neighbour-2G", "", icon("wifi-1", 22), icon("lock", 18)), li("Other network…", "", icon("plus", 22)))}""", back=True)

page("sticky-wifi-add", "Phone hotspot", f"""
<div class=fld><div class=s>Password</div><div style="font-size:20px;font-weight:600;margin-top:6px;letter-spacing:2px">••••••••••</div></div>
<div class="btn on">Add to the Sticky</div>""", back=True)

page("transcripts", "Transcripts", f"""
{sec("Today")}
{lst(li("Live · started 10:35 · Office", "“…the launch checklist, who owns the launch checklist…”", "", '<span class="pill on sp">Live</span>'),
     li("Product launch and Partner Plans", "13:12 · Office · 1 h 11 min · 11 to-dos", "", f'<span class="pill sp" style="background:#fff">{STAR}2</span>{CHEV}'),
     li("Quick note", "14:33 · 3 min · no to-dos", "", CHEV))}
{sec("Yesterday")}
{lst(li("Weekly planning", "13:09 · Home · 2 min · no to-dos", "", CHEV))}""", "Transcripts")

TOTAL = 71 * 60 + 12
MARKS = [12 * 60 + 40, 40 * 60 + 47]
POS = 40 * 60 + 9


def pct(sec_):
    return f"{sec_ * 100 / TOTAL:.1f}%"


def tl(at, text, cls="", who=""):
    star = STAR if "star" in cls else ""
    chip = f"<span class=who>{who}</span>" if who else ""
    return f'<div class="tl {cls.replace("star", "")}"><span class=at>{at}{star}</span><span>{chip}{text}</span></div>'


def lines(rows):
    return '<div style="display:flex;flex-direction:column;margin:0 -12px">' + "".join(tl(*r) for r in rows) + "</div>"


def marks_row():
    return '<div class=row style="gap:8px"><span class=s style="font-weight:700">Marks</span>' + "".join(
        pill(f"{STAR}{m // 60:02d}:{m % 60:02d}") for m in MARKS) + "</div>"


def player(options=""):
    return f"""<div class=player>
<div class=seek><div class=track></div><div class=done style="width:{pct(POS)}"></div><div class=knob style="left:{pct(POS)}"></div>""" + "".join(
        f'<span class=mk style="left:{pct(m)}">{STAR}</span>' for m in MARKS) + f"""</div>
<div class=row style="font-size:12px;font-weight:700;color:#666"><span>40:09</span><span class=sp>1:11:12</span></div>
<div class=ctl>{icon("rewind-backward-15", 30)}<span class=play>{icon("player-pause-filled", 26, 2, "#fff")}</span>{icon("rewind-forward-15", 30)}</div>{options}
</div>"""


PILLS = '<div class=row style="gap:8px">' + pill("13:12") + pill("Office") + pill("1 h 11 min") + pill("Gemini Nano") + "</div>"
SUMMARY = '<div class=card><div class=sec style="padding:0 0 8px">Summary</div><p class=q>The team plans the new server rollout and a test environment for two customer users. Priya and Alex meet on Friday.</p></div>'
TODOS = sec("To-dos · 11") + lst(li("Restore the test environment by tomorrow", "", chk(False)), li("Meet Priya and Alex on Friday", "", chk(True)).replace("<b>", '<b style="text-decoration:line-through;color:#888">'))
PLAIN = [
    ("38:52", "And the backup job moves to the new box at the same time."),
    ("39:05", "Priya asked if the customers see any downtime.", "near"),
    ("39:21", "No, we switch the DNS at night and keep the old servers for two days.", "near"),
    ("39:40", "Then Alex checks the time zone issue on the test environment.", "near"),
    ("40:02", "We must restore the test environment by tomorrow, that is the blocker.", "now"),
    ("40:19", "Okay, two customer users on Friday.", "near"),
    ("40:47", "Send Northwind the claims list so they can resubmit.", "near star"),
    ("41:02", "Fine. Next point is the contract with Contoso."),
]
page("transcript", "Product launch", PILLS + SUMMARY + TODOS + sec("Transcript") + marks_row() + lines([
    ("00:00", "Okay, let's start with the rollout plan."), ("00:07", "Sure. The two staging servers are ready next week.")]), back=True, foot=player())
page("transcript-player", "Product launch", sec("Transcript") + lines(PLAIN), back=True, foot=player())

SPEAKERS = [
    ("38:52", "And the backup job moves to the new box at the same time.", "", "Speaker B"),
    ("39:05", "<i>-----</i> Do the customers see any downtime?", "near", "Priya"),
    ("39:21", "No, we switch the DNS at night and keep the old servers for two days.", "near", "Alex"),
    ("39:40", "Then I check the time zone issue on the <i>-----</i> test environment.", "near", "Alex"),
    ("40:02", "We must restore the test environment by tomorrow, that is the blocker.", "now", "Priya"),
    ("40:19", "<i>-----</i> Two customer users on Friday.", "near", "Alex"),
    ("40:47", "Send Northwind the claims list so they can resubmit.", "near star", "Speaker B"),
]
OPTIONS = f"""
<div class=row style="gap:8px;justify-content:center"><span class="opt on">{icon("check", 14, 3, "#fff")}Clean up</span><span class=opt>Skip silence</span></div>
<div class=row style="gap:10px;justify-content:center"><span class=s style="font-weight:700">Crisp voice</span>{seg(["Off", "Clear", "Clear + denoise"], 1, big=False)}</div>"""
page("transcript-speakers", "Product launch", sec("Transcript") + marks_row() + lines(SPEAKERS), back=True, foot=player(OPTIONS))

page("speaker-rename", "Product launch", sec("Transcript") + marks_row() + lines(SPEAKERS), back=True, foot=player(OPTIONS) + SHEET + """
<div class=h style="font-size:20px;padding:0 4px">Name Speaker A</div>
<div class=s style="padding:0 4px">For the whole session. The summary and the to-dos use the name.</div>
<div class=fld><div class=s>Name</div><div style="font-size:20px;font-weight:600;margin-top:6px">Priya</div></div>
<div class=s style="padding:0 4px">“We must restore the test environment by tomorrow, that is the blocker.”</div>
<div class="btn on">Save</div></div>""")

THUMB = 'border-radius:10px;background:linear-gradient(135deg,#e6e6e6,#b9b9b9);display:flex;align-items:center;justify-content:center;flex:none'
PHOTO_ROW = f'''<div class="tl near"><span class=at>39:30{icon("camera", 13, 2.2, "#666")}</span>
<div style="flex:1;display:flex;gap:10px;align-items:center"><div style="width:96px;height:68px;{THUMB}">{icon("photo", 28, 2, "#555")}</div>
<div><div class=h style="font-size:14px">Whiteboard</div><div class=s>“Restore test env · DNS switch Fri”</div></div></div></div>'''
rows = [tl(*r) for r in PLAIN[:7]]
rows.insert(3, PHOTO_ROW)
CAM_MARK = f'<span class=mk style="left:{pct(39 * 60 + 30)};top:-7px">{icon("camera", 14, 2.2)}</span>'
page("transcript-photo", "Product launch", '<div class=row style="gap:8px">' + pill("13:12") + pill("Office") + pill("1 h 11 min") + pill(f'{icon("photo", 14)} 1') + "</div>"
     + sec("Transcript") + '<div style="display:flex;flex-direction:column;margin:0 -12px">' + "".join(rows) + "</div>",
     back=True, foot=player().replace("</div>\n<div class=row", CAM_MARK + "</div>\n<div class=row", 1))

page("photo-view", "Whiteboard · 39:30", f"""
<div style="height:420px;{THUMB};border-radius:20px">{icon("photo", 64, 1.6, "#666")}</div>
{sec("Text in the photo")}
<div class=card><p class=q>Restore test env by tomorrow · DNS switch Friday night · keep old servers 2 days</p></div>
<div class=btn>{icon("device-mobile", 20)}Show on Sticky</div>
<div class="btn on">{icon("player-play", 20, 2, "#fff")}Play from 39:30</div>""", back=True)

page("qs-tile", "", f"""
<div style="position:absolute;left:0;top:0;width:412px;height:915px;background:#1b1b1b"></div>
<div style="position:absolute;left:16px;top:60px;width:380px;display:grid;grid-template-columns:1fr 1fr;gap:10px">""" + "".join(
    f'<div style="height:72px;border-radius:36px;background:{bg};color:{fg};display:flex;align-items:center;gap:12px;padding:0 20px;font-size:15px;font-weight:700">{icon(i, 24, 2, fg)}<span>{n}<br><span style="font-weight:500;font-size:12px">{d}</span></span></div>'
    for i, n, d, bg, fg in [("wifi", "Internet", "Home Wi-Fi", "#3a3a3a", "#fff"), ("bluetooth", "Bluetooth", "Sticky", "#3a3a3a", "#fff"),
                            ("camera", "Snap to meeting", "Product launch · live", "#fff", "#111"), ("moon", "Do not disturb", "Off", "#3a3a3a", "#fff")]) + "</div>")

page("todo", "To-do", f"""
<div class=row style="gap:8px"><span class="pill on">Open · 6</span>{pill("Done today · 1")}</div>
""" + lst(*[li(t, f, chk(False)) for t, f in [
    ("Restore the test environment", "Product launch and Partner Plans · 13:12"),
    ("Check the time zone issue", "Product launch and Partner Plans · 13:12"),
    ("Set up two customer users", "Product launch and Partner Plans · 13:12"),
    ("Send Northwind the claims to resubmit", "Product launch and Partner Plans · 13:12"),
    ("Compare DNS providers and pricing", "Product launch and Partner Plans · 13:12"),
    ("Review the new dashboard on Wednesday", "Weekly planning · Nov 9 13:09"),
]]), "To-do")

CLOCKS = [("clock", "Dial"), ("clock-digits", "Digits"), ("clock-words", "Words"), ("clock-note", "Note"), ("clock-flip", "Flip")]
WEATHERS = [("weather", "Sketch"), ("weather-big", "Big picture"), ("weather-journal", "Journal"), ("weather-week", "Week")]


def strip(items, on):
    return '<div style="display:flex;gap:12px;overflow:hidden">' + "".join(
        f'<div style="flex:none;width:118px;display:flex;flex-direction:column;align-items:center;gap:6px"><img src="../{f}.png" style="width:118px;border-radius:14px;border:{"4px solid #111" if i == on else "2px solid #ddd"}">'
        f'<span class=row style="gap:6px;font-size:13px;font-weight:700"><span class="radio{" on" if i == on else ""}" style="width:16px;height:16px"></span>{n}</span></div>' for i, (f, n) in enumerate(items)) + "</div>"


page("faces", "Clock", sec("Clock face") + strip(CLOCKS, 0) + sec("Hours") + "<div>" + seg(["24 h", "12 h"], 0) + "</div>", back=True)

page("weather-settings", "Weather", sec("Style") + strip(WEATHERS, 0) + lst(li("Lisbon · 18° · Cloudy", "High 21° · low 12° · rain 20% · Updated 10:42", "", icon("refresh", 20)))
     + sec("Location") + lst(li("From phone", "Approximate location, about 10 km", radio(True)), li("Fixed city", "Lisbon", radio(False)))
     + sec("Units") + lst(li("°C", "Celsius", radio(True)), li("°F", "Fahrenheit", radio(False)))
     + sec("Refresh") + "<div>" + seg(["15 min", "30 min", "1 h", "3 h"], 1) + "</div>", back=True)

page("places", "Places", f"""
<div class=s style="padding:0 4px">Each new recording gets the name of the place where it starts. Locations stay on this phone and are never sent anywhere.</div>
{lst(li("Allow all the time", "Tags recordings while the app is closed. On the next screen, choose “Allow all the time”", "", CHEV))}
{sec("Your places")}
{lst(li("Home", "150 m radius", icon("map-pin", 22), CHEV), li("Office", "100 m radius", icon("map-pin", 22), CHEV))}
<div class=btn>{icon("plus", 20)}Add this place</div>""", back=True)

page("place-edit", "Add this place", f"""
<div class=fld><div class=s>Name</div><div style="font-size:20px;font-weight:600;margin-top:6px">Office</div></div>
{sec("Radius")}
{chips(["100 m", "150 m", "300 m"], 1)}
{lst(li("Location found", "Within 12 m · tap to measure again", icon("map-pin", 22), icon("refresh", 20)))}
<div class="btn on">Add place</div>""", back=True)


def acct(email, cals):
    return f'<div class=sec style="text-transform:none;letter-spacing:0;font-size:14px">{email}</div>' + lst(*[li(n, "", chk(on)) for n, on in cals])


page("calendars", "Calendars", f"""
<div class=card><div class=row><div><div class=h>Show on the Sticky</div><div class=s>Clock shows today and tomorrow</div></div><span class="sw sp"></span></div></div>
""" + acct("you@work.example", [("Work", True), ("Team rota", True), ("Holidays", False)])
     + acct("you@gmail.example", [("Personal", True), ("Family", True)])
     + f'<div class=btn>{icon("plus", 20)}Add a Google account</div>', back=True)


def chart(title, rng, now, pts, legend=""):
    path = " ".join(f"{i * 300 / (len(pts) - 1):.0f},{60 - v * 56:.0f}" for i, v in enumerate(pts))
    return f"""<div class=card><div class=row><div><div class=h>{title}</div><div class=s>{rng}</div></div><span class="sp h" style="font-size:20px">{now}</span></div>
<svg width="340" height="64" style="margin-top:12px"><line x1=0 y1=2 x2=340 y2=2 stroke="#e2e2e2"/><line x1=0 y1=32 x2=340 y2=32 stroke="#e2e2e2"/><line x1=0 y1=62 x2=340 y2=62 stroke="#e2e2e2"/>
<polyline transform="scale(1.133,1)" points="{path}" fill=none stroke="#111" stroke-width=2 stroke-linejoin=round /></svg>
<div class=row style="margin-top:4px"><span class=s>24 h ago</span><span class="s sp">Now</span></div>{legend}</div>"""


page("health", "Health", chips(["6 h", "24 h", "7 d"], 1)
     + chart("Battery", "Min 61 % · max 100 %", "93 %", [1, .98, .95, .9, .86, .8, .74, .7, .66, .62, .95, 1, .97, .93])
     + chart("Internal RAM", "Min 58 KB · max 64 KB", "61 KB", [.6, .62, .58, .55, .6, .61, .57, .59, .6, .58, .56, .6, .61, .6],
             '<div class=row style="margin-top:6px"><span class=s style="color:#111">Free</span><span class="s sp" style="color:#111">61 KB</span></div><div class=row><span class=s style="color:#111">Lowest</span><span class="s sp" style="color:#111">58 KB</span></div>')
     + chart("Chip temperature", "Min 31.0 °C · max 36.5 °C", "34.0 °C", [.3, .35, .4, .5, .55, .6, .62, .58, .5, .45, .48, .52, .5, .5], ""), back=True)

page("pipeline", "Pipeline", lst(
    li("Chunk 15 · received", "waiting for speech-to-text", icon("circle-dashed", 22)),
    li("Chunk 14 · transcribing", "whisper base.en q8_0 · started 1 s ago", icon("loader-2", 22)),
    li("Chunk 13 · done", "whisper base.en q8_0 · 2.2 s · 61 words", icon("circle-check", 22)),
    li("Chunk 12 · done", "whisper base.en q8_0 · 2.1 s · 58 words", icon("circle-check", 22)),
    li("Summary · Product launch", "Gemini Nano · 5.0 s · 11 to-dos", icon("brain", 22)),
    li("Chunk 11 · waiting", "whisper base.en q8_0 is downloading (64%)", icon("circle-dashed", 22))), back=True)

page("models", "Models", sec("Speech-to-text") + lst(
    li("whisper tiny.en q8_0", "On phone · 44 MB · 0.9 s per 30 s", radio(False)),
    li("whisper base.en q8_0", "On phone · 82 MB · 2.2 s per 30 s", radio(True), DEFAULT),
    li("whisper base.en", "On phone · 148 MB", radio(False), icon("download", 20)),
    li("whisper small.en q8_0", "On phone · 264 MB", radio(False), icon("download", 20)),
    li("whisper medium.en q5_0", "On phone · 539 MB", radio(False), icon("download", 20)))
    + sec("Summaries · app open") + lst(li("Gemini Nano", "Built in · 5.0 s", radio(True), DEFAULT), li("Gemma 4 E2B", "On phone · 2.6 GB · 48 s", radio(False)))
    + sec("Summaries · app closed") + lst(li("Gemma 4 E2B", "On phone · 2.6 GB · 48 s", radio(True), DEFAULT), li("Wait for the laptop", "Codex gpt-6-luna · after hub sync", radio(False))), back=True)

page("settings", "App settings", f"""
<div class=card><div class=row><div><div class=h>This phone is a hub</div><div class=s>The Sticky sends audio here</div></div><span class="sw sp"></span></div></div>
{lst(li("Models", "whisper base.en q8_0 · Gemini Nano", icon("brain", 22), CHEV), li("Pipeline", "Idle", icon("activity", 22), CHEV),
     li("Places", "Home, Office", icon("map-pin", 22), CHEV), li("Calendars", "4 calendars on the Sticky", icon("calendar", 22), CHEV),
     li("Updates", "Version 0.2.0 is ready", icon("download", 22), '<span class="pill sp on">New</span>' + CHEV))}
{sec("Transcribe")}
{lst(li("Right away", "Each chunk when it arrives", radio(True), DEFAULT), li("Every 15 minutes", "In one batch every 15 minutes", radio(False)), li("Only while charging", "Chunks wait until the phone charges", radio(False)))}
{sec("Hubs")}
{lst(li("This phone", "192.168.1.20", icon("server", 22)))}""", back=True)

page("updates", "Updates", f"""
{sec("Phone app")}
<div class=card><div class=row><div><div class=h>resticky 0.1.0</div><div class=s>Latest release 0.2.0 · Nov 10</div></div></div>
<div class="btn on" style="margin-top:14px">{icon("download", 20, 2, "#fff")}Download and install</div>
<div class=s style="margin-top:10px">Android asks you to confirm the install.</div></div>
{sec("Sticky firmware")}
{lst(li("Sticky · desk", "0.14.3 · latest 0.14.4", icon("device-mobile", 22), '<span class="pill sp on">Update now</span>'))}
<div class=s style="padding:0 4px">Checked today 10:42 · github.com/mtch3n/resticky</div>
<div class=btn>{icon("refresh", 20)}Check for updates</div>""", back=True)

BAR = lambda pct: f'<div class=bar2 style="margin-top:8px"><div style="width:{pct}%"></div></div>'
def fwrow(title, sub, trail="", bar=None):
    extra = BAR(bar) if bar is not None else ""
    return f'<div class=li><span class=t style="flex:1"><b>{title}</b><span class=s>{sub}</span>{extra}</span>{trail}</div>'
page("firmware-states", "Sticky settings", sec("Firmware · each state") + '<div class=list>' + "".join([
    fwrow("Firmware 0.14.4", "Latest 0.14.6 · checked today 10:42", '<span class="pill sp on">Update now</span>'),
    fwrow("1 Download 0.14.6", "42 % · from GitHub", '<span class="pill sp" style="background:#fff">Cancel</span>', bar=42),
    fwrow("2 Send to the Sticky", "63 % · 18 KB/s · keep the phone near", '<span class="pill sp" style="background:#fff">Cancel</span>', bar=63),
    fwrow("3 Install 0.14.6", "The Sticky restarts · waiting for it to connect again"),
    fwrow("4 Done · 0.14.6", "The Sticky checks itself for 3 minutes", '<span class="pill sp" style="background:#fff">Check for updates</span>'),
    fwrow("Update cancelled", "Update now starts again from the download", '<span class="pill sp" style="background:#fff">Try again</span>'),
    fwrow("Update failed", "Charge the Sticky to 20 % or connect USB first", '<span class="pill sp" style="background:#fff">Try again</span>'),
    fwrow("Rolled back to 0.14.4", "The new firmware failed its check", '<span class="pill sp" style="background:#fff">Try again</span>'),
]) + "</div>", back=True)


GRID = lambda n: '<div style="display:grid;grid-template-columns:repeat(3,1fr);gap:8px">' + "".join(
    f'<div style="height:118px;{THUMB};border-radius:12px">{icon("photo", 24, 2, "#666")}</div>' for _ in range(n)) + "</div>"
page("photos", "Photos", sec("Product launch · today 13:12") + GRID(3) + sec("Weekly planning · yesterday 09:30") + GRID(2) + sec("Not linked")
     + lst(li("Today 08:12", "No session within 10 min", f'<div style="width:72px;height:52px;{THUMB}">{icon("photo", 20, 2, "#666")}</div>', '<span class=sp style="font-size:14px;font-weight:700">Attach</span>')), "Photos")

page("home-screen", "Home screen", lst(*[li(n, d, radio(i == 0)) for i, (n, d) in enumerate([
    ("Apps", "All apps as tiles"), ("Clock", "The clock face"), ("Weather", "The weather style"), ("Recorder", "Recording state and level"), ("To-do", "Open to-dos")])])
    + '<div class=s style="padding:0 4px">The Sticky shows this after start and on Home; tap the app name to open all apps.</div>', back=True)
