import os, re, sys
root = sys.argv[1]; A = root + "/assets"
def icon(name, size=56, sw=2.2, color="#000"):
    s = open(f"{A}/icons/{name}.svg").read()
    s = re.sub(r'width="24"', f'width="{size}"', s); s = re.sub(r'height="24"', f'height="{size}"', s)
    s = s.replace('stroke-width="2"', f'stroke-width="{sw}"').replace('currentColor', color)
    return re.sub(r'class="[^"]*"', '', s)
CSS = f"""
@font-face{{font-family:M;src:url(assets/fonts/Montserrat.ttf)}}
*{{box-sizing:border-box;margin:0;padding:0}}
body{{width:480px;height:800px;overflow:hidden;background:#fff;color:#000;font-family:M;font-weight:500;display:flex;flex-direction:column}}
.status{{height:44px;display:flex;justify-content:space-between;align-items:center;padding:0 24px;font-size:18px;font-weight:600}}
.status span{{display:flex;align-items:center;gap:6px}}
.title{{font-family:M;font-weight:700;font-size:30px;padding:2px 24px 0}}
.clock{{font-size:96px;font-weight:700;letter-spacing:-3px;line-height:1;padding:8px 24px 0}}
.date{{font-size:22px;font-weight:600;padding:6px 26px 0}}
.apps{{display:grid;grid-template-columns:1fr 1fr 1fr;gap:18px 0;padding:28px 12px}}
.app{{display:flex;flex-direction:column;align-items:center;gap:10px;font-size:19px;font-weight:600}}
.ic{{width:104px;height:104px;border:3px solid #000;border-radius:28px;display:flex;align-items:center;justify-content:center}}
.ic.on{{background:#000}}
.sub{{font-size:15px;font-weight:600;margin-top:-6px}}
.card{{margin:0 20px;border:3px solid #000;border-radius:24px;padding:20px 22px;display:flex;flex-direction:column;gap:12px}}
.pill{{display:inline-flex;align-items:center;gap:8px;background:#000;color:#fff;border-radius:999px;padding:6px 16px;font-size:18px;font-weight:700}}
.nums{{display:flex;justify-content:space-between}}
.nums div{{font-size:16px;font-weight:600}} .nums b{{display:block;font-size:34px;font-weight:700}}
.btn{{height:64px;border:3px solid #000;border-radius:999px;display:flex;align-items:center;justify-content:center;gap:10px;font-size:20px;font-weight:700}}
.btn.on{{background:#000;color:#fff}}
.row{{min-height:64px;display:flex;align-items:center;gap:16px;padding:0 24px;font-size:21px;font-weight:500}}
.status + .row{{margin-top:12px}}
.row .v{{margin-left:auto;font-weight:500;font-size:18px;display:flex;align-items:center;gap:4px}}
.quote{{font-size:21px;line-height:1.4;font-weight:500}}
.tabs{{margin-top:auto;height:72px;border-top:3px solid #000;display:flex}}
.tabs div{{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px;font-size:15px;font-weight:700}}
.tabs .on{{background:#000;color:#fff}}
"""
def page(name, body):
    open(f"{root}/{name}.html","w").write(f"<!doctype html><meta charset=utf-8><style>{CSS}</style><body>{body}</body>")
STATUS = f'<div class=status><span>{icon("wifi",22,2.4)}</span><span>78% {icon("battery-3",28,2)}</span></div>'
CSS3 = """
.hdr{display:flex;align-items:center;gap:4px;padding:2px 16px 10px;font-size:30px;font-weight:700;border-bottom:2px solid #000}
.sec{font-size:16px;font-weight:800;padding:24px 24px 6px;text-transform:uppercase;letter-spacing:1.5px}
.seg{display:flex;margin:0 24px;border:3px solid #000;border-radius:999px;overflow:hidden;height:60px}
.seg div{flex:1;display:flex;align-items:center;justify-content:center;font-size:18px;font-weight:700}
.seg .on{background:#000;color:#fff}
.sw{margin-left:auto;width:104px;height:48px;border:3px solid #000;border-radius:999px;position:relative;display:flex;align-items:center;justify-content:flex-end;padding:0 14px;font-size:17px;font-weight:800;letter-spacing:1px}
.sw:after{content:'';position:absolute;top:5px;left:5px;width:32px;height:32px;border-radius:50%;background:#000}
.sw.on{background:#000;color:#fff;justify-content:flex-start}.sw.on:after{left:auto;right:5px;background:#fff}
.step{display:flex;align-items:center;gap:14px;margin-left:auto}
.step b{font-size:24px;min-width:80px;text-align:center}
.rb{width:52px;height:52px;border:3px solid #000;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:28px;font-weight:700}
.bar{margin:0 24px;height:28px;border:3px solid #000;border-radius:999px;overflow:hidden}
.bar div{height:100%;background:#000}
.btns{display:flex;flex-direction:column;gap:14px;padding:18px 24px}
.chk{width:40px;height:40px;border:3px solid #000;border-radius:10px;display:flex;align-items:center;justify-content:center;flex:none}
.chk.on{background:#000}
"""
BACK = ''
def page3(name, body):
    open(f"{root}/{name}.html","w").write(f"<!doctype html><meta charset=utf-8><style>{CSS}{CSS3}</style><body>{body}</body>")
RB = 'width:64px;height:64px;border:3px solid #000;border-radius:50%;display:flex;align-items:center;justify-content:center'
def PAGES(page):
    a, b = [x.strip() for x in page.split("/")]
    hide = ";visibility:hidden"
    return (f'<div style="margin-top:auto;height:96px;flex:none;display:flex;align-items:center;justify-content:space-between;padding:0 24px">'
            f'<span style="{RB}{hide if a == "1" else ""}">{icon("chevron-left",34,2.6)}</span><b style="font-size:22px">{page}</b>'
            f'<span style="{RB}{hide if a == b else ""}">{icon("chevron-right",34,2.6)}</span></div>')
def H(t, page=''): return (f'<div class=status><span style="gap:10px">{icon("wifi",22,2.4)}<b style="font-size:22px;font-weight:700">{t}</b></span>'
                            f'<span style="gap:16px"><span style="display:flex;align-items:center;gap:6px">78% {icon("battery-3",28,2)}</span></span></div>')
CHK = icon("check",28,3,"#fff") if os.path.exists(f"{A}/icons/check.svg") else "✓"
PAGER = ''
RTABS = lambda on: '<div class=tabs>' + ''.join(f'<div class={"on" if n==on else ""}>{icon(i,34,2.2,"#fff" if n==on else "#000")}{n}</div>' for n,i in [("Home","home"),("Transcripts","file-text")]) + '</div>'
def T(t, page=''): return H(t, page)


def REC(state, sub, btn, on):
    dot = '<span style="width:14px;height:14px;border-radius:50%;background:#000"></span>' if on else ''
    return f'''<div style="display:flex;align-items:center;gap:16px;padding:20px 24px">
<div style="display:flex;flex-direction:column;gap:4px"><div style="display:flex;align-items:center;gap:10px;font-size:26px;font-weight:700">{dot}{state}</div><div style="font-size:18px;font-weight:600">{sub}</div></div>
</div>'''
page3("recorder", H("Recorder") + REC("Recording", "2 h 14 today", "Mute", True) + f'''
<div class=card><div style="font-size:17px;font-weight:700">10:42 · Billing call</div>
<div class=quote>“…so we ship the billing report on Friday, and Sam checks the Q3 numbers first.”</div></div>''')
page3("settings", T("Settings") + ''.join(
 f'<div class=row>{icon(i,32)}{n}<span class=v>{v} {icon("chevron-right",24)}</span></div>'
 for i,n,v in [("microphone","Recording","16 kbps"),("wifi","Wi-Fi","HomeWiFi"),("device-mobile-rotated","Hub","OK"),("cloud","Weather","Phone"),("hand-finger","Buttons",""),("device-mobile-rotated","Device","v0.4.3")]) + BACK)


page3("recorder-muted", H("Recorder") + REC("Muted", "12 min", "Record", False))
page3("transcripts", H("Transcripts") + '<div class=sec>Today · 14</div>' + ''.join(
 f'<div class=row style="flex-direction:column;align-items:flex-start;justify-content:center;gap:2px;min-height:64px"><span>{t}</span><span style="font-size:16px;font-weight:500">{d}</span></div>'
 for t,d in [("10:42 · Billing call","2 to-dos"),("09:15 · Stand-up","1 to-do"),("08:50 · Note to self","1 to-do"),("08:30 · Phone call","0 to-dos"),("08:05 · Planning","3 to-dos"),("Yesterday 16:40 · Design review","2 to-dos"),("Yesterday 14:10 · Hiring call","1 to-do")])
 + PAGES("1 / 3"))
page3("transcript", H("Billing call") + f'''<div class=sec>10:42 · 18 min</div>
<div class=quote style="padding:0 24px">Billing report ships Friday. Sam checks the Q3 numbers first. Aurora summary after lunch.</div>
<div class=sec>To-dos</div>
<div class=row><span class=chk></span>Ship billing report (Fri)</div>
<div class=row><span class="chk on">{CHK}</span>Send Aurora summary</div>''')
BATT = [64, 60, 57, 53, 49, 46, 42, 39, 70, 98, 100, 99, 97, 96, 95, 93, 92, 90, 88, 86, 84, 82, 80, 78]
def chart(vals, w=432, h=150):
    pts = " ".join(f"{40 + k * (w - 40) / (len(vals) - 1):.0f},{8 + (100 - v) * (h - 16) / 100:.0f}" for k, v in enumerate(vals))
    grid = "".join(f'<line x1=40 x2={w} y1={8 + (100 - g) * (h - 16) / 100:.0f} y2={8 + (100 - g) * (h - 16) / 100:.0f} stroke="#000" stroke-width=2 stroke-dasharray="2 8"/>'
                   f'<text x=0 y={14 + (100 - g) * (h - 16) / 100:.0f} font-size=16 font-weight=600>{g}%</text>' for g in (100, 50, 0))
    return (f'<svg width={w} height={h + 26} style="margin:4px 24px 0;font-family:M;flex:none;overflow:visible">{grid}'
            f'<polyline points="{pts}" fill=none stroke="#000" stroke-width=4 stroke-linejoin=round stroke-linecap="round"/>'
            f'<text x=40 y={h + 22} font-size=16 font-weight=600>24 h ago</text><text x={w} y={h + 22} font-size=16 font-weight=600 text-anchor=end>now</text></svg>')
page3("stats", H("Stats") + f'''
<div style="padding:16px 24px 0"><div style="font-size:56px;font-weight:700;letter-spacing:-2px;line-height:1.1">4.2 days</div><div style="font-size:18px;font-weight:600">battery left · 78% · 162 mAh/day</div></div>
<div class=sec>Battery · last 24 h</div>{chart(BATT)}
<div class=sec>Today</div>
<div class=row>Speech<span class=v><b>2 h 14</b></span></div>
<div class=row>Uploads<span class=v><b>14 · 0 failed</b></span></div>
<div class=row>Audio sent<span class=v><b>1.6 MB</b></span></div>''' + BACK + PAGES("1 / 2"))
page3("settings-recording", T("Recording") + f'''<div class=row>Microphone<span class="sw on">ON</span></div><div class=sec>Quality</div><div class=seg><div>8 kbps</div><div>12 kbps</div><div class=on>16 kbps</div></div>
<div style="height:16px"></div><div class=row>Stop when face down<span class=sw>OFF</span></div>''' + BACK)
page3("settings-wifi", T("Wi-Fi") + '<div class=sec>Saved networks</div>' + ''.join(
 f'<div class=row style="{"" if k==0 else ""}"><span style="display:flex;flex-direction:column"><span>{n}</span><span style="font-size:16px;font-weight:500">{s}</span></span><span class=v><span class=btn style="height:52px;width:120px;font-size:18px">Forget</span></span></div>'
 for k,(n,s) in enumerate([("HomeWiFi","Connected"),("Office-5G","Saved"),("Phone hotspot","Saved")]))
 + '<div style="padding:18px 24px;font-size:16px;font-weight:600">Add a network in the phone app: Sticky › Wi-Fi.</div>' + BACK)
page3("settings-hub", T("Hub") + f'''<div class=row>Name<span class=v>laptop</span></div>
<div class=row>Status<span class=v>OK</span></div>
<div class=btns><div class=btn>Test connection</div></div>''' + BACK)
page3("settings-device", T("Device") + f'''<div class=row>Firmware<span class=v>0.4.3</span></div>
<div style="flex:1"></div><div class=btns style="padding-bottom:28px"><div class=btn>Restart</div><div class=btn>Shut down</div></div>''' + BACK)




page3("todo", T("To-do") + '<div class=sec>Open · 11</div>' + ''.join(
 f'<div class=row style="{"" if i==0 else ""}min-height:66px"><span class=chk></span><span style="display:flex;flex-direction:column;gap:2px"><span>{t}</span><span style="font-size:16px;font-weight:500">{d}</span></span></div>'
 for i,(t,d) in enumerate([("Restore the test environment","Product launch · 13:12"),("Check the time zone issue","Product launch · 13:12"),("Meet Priya and Alex on Friday","Product launch · 13:12"),("Set up two customer users","Product launch · 13:12"),("Review the new dashboard","Product launch · 13:12"),("Send Northwind the claims","Product launch · 13:12"),("Compare DNS providers","Product launch · 13:12")]))
 + PAGES("1 / 3") + BACK)
STATUS_MOON = f'<div class=status><span>{icon("moon",22,2.4)}</span><span>78% {icon("battery-3",28,2)}</span></div>'

page3("sleep", f'''<div style="flex:1;display:flex;flex-direction:column;justify-content:center;align-items:center;gap:16px">
{icon("moon",120,2)}
<div style="font-size:40px;font-weight:700;margin-top:10px">Sleeping</div>
<div style="font-size:20px;font-weight:600">Recording is paused</div>
<div style="font-size:20px;font-weight:600;margin-top:30px">Press any button to wake</div></div>''')
def BROW(top, press, action, fixed=False):
    arrow = "" if fixed else icon("chevron-right", 24)
    return f'<div class=row style="min-height:58px;{"" if top else ""}">{press}<span class=v>{action} {arrow}</span></div>'
page3("settings-buttons", T("Buttons") + '<style>.sec{padding:12px 24px 6px}</style>'
 + '<div class=sec>Power</div>' + BROW(True, "Press", "Sleep / wake") + BROW(False, "Hold 10 s", "Power off", True)
 + '<div class=sec>Up</div>' + BROW(True, "Press", "Previous page") + BROW(False, "Hold", "Mark moment")
 + '<div class=sec>Down</div>' + BROW(True, "Press", "Next page") + BROW(False, "Hold", "Mute / record")
 + BACK)
KEY = 'position:absolute;background:#000;border-radius:6px'
CALL = 'position:absolute;font-size:17px;font-weight:600;line-height:1.3'
def KEYC(y, h, ty, text):
    mid = y + h // 2
    return (f'<div style="{KEY};left:210px;top:{y}px;width:8px;height:{h}px"></div>'
            f'<div style="position:absolute;left:220px;top:{mid-1}px;width:16px;height:2px;background:#000"></div>'
            f'<div style="position:absolute;left:235px;top:{min(mid,ty+11)}px;width:2px;height:{abs(mid-ty-11)+2}px;background:#000"></div>'
            f'<div style="position:absolute;left:236px;top:{ty+10}px;width:10px;height:2px;background:#000"></div>'
            f'<div style="{CALL};left:252px;top:{ty}px;width:220px">{text}</div>')
page3("button-map", f'''<div style="position:relative;flex:1">
<div style="position:absolute;left:40px;top:140px;width:170px;height:300px;border:4px solid #000;border-radius:24px"></div>
<div style="position:absolute;left:54px;top:154px;width:142px;height:272px;border:2px solid #000;border-radius:10px;display:flex;align-items:center;justify-content:center;font-size:16px;font-weight:700">screen</div>
{KEYC(175, 22, 100, "<b>Power</b><br>press: sleep / wake<br>hold 10 s: power off")}
{KEYC(235, 34, 222, "<b>Up</b><br>press: previous page<br>hold: ★ mark moment")}
{KEYC(277, 34, 344, "<b>Down</b><br>press: next page<br>hold: mute / record")}
<div style="{CALL};left:24px;top:500px;width:432px">Any button wakes the Sticky from sleep.</div>
</div>''')


TEMPS = [18, 19, 20, 21, 21, 21, 20, 19, 17, 16, 15, 14, 14]
MARKS = [(0, "now", "cloud"), (3, "14:00", "sun"), (6, "17:00", "cloud"), (9, "20:00", "cloud-rain"), (12, "23:00", "cloud-rain")]
DAYS = [("Tue", "cloud-rain", 11, 17), ("Wed", "cloud", 12, 19), ("Thu", "sun", 13, 22), ("Fri", "cloud-storm", 12, 18), ("Sat", "sun", 12, 21)]
def tcurve(w=432, h=128):
    x = lambda k: 22 + k * (w - 44) / 12
    y = lambda t: 34 + (21 - t) * 62 / 7
    pts = " ".join(f"{x(k):.0f},{y(t):.0f}" for k, t in enumerate(TEMPS))
    marks = "".join(f'<circle cx={x(k):.0f} cy={y(TEMPS[k]):.0f} r=6 fill="#000"/>'
                    f'<text x={x(k):.0f} y={y(TEMPS[k]) - 14:.0f} font-size=20 font-weight=700 text-anchor=middle>{TEMPS[k]}°</text>'
                    f'<text x={x(k):.0f} y={h - 4} font-size=16 font-weight=600 text-anchor=middle>{t}</text>' for k, t, _ in MARKS)
    icons = "".join(f'<div style="position:absolute;left:{24 + x(k) - 16:.0f}px;top:0">{icon(i, 32)}</div>' for k, _, i in MARKS)
    return (f'<div style="position:relative;flex:none;height:{h + 36}px;padding-top:36px">{icons}'
            f'<svg width={w} height={h} style="margin-left:24px;font-family:M;overflow:visible">'
            f'<polyline points="{pts}" fill="none" stroke="#000" stroke-width="4" stroke-linejoin="round" stroke-linecap="round"/>{marks}</svg></div>')
def range_bar(lo, hi, a=10, b=23, w=170):
    l, r = (lo - a) * w / (b - a), (hi - a) * w / (b - a)
    return (f'<span style="position:relative;width:{w}px;height:12px;flex:none"><span style="position:absolute;left:0;right:0;top:5px;height:2px;background:#000"></span>'
            f'<span style="position:absolute;left:{l:.0f}px;width:{r - l:.0f}px;top:0;height:12px;border-radius:6px;background:#000"></span></span>')
page3("weather", H("Weather") + f'''
<div style="display:flex;align-items:center;gap:20px;padding:10px 24px 0">
<div style="font-size:104px;font-weight:700;line-height:1;letter-spacing:-4px">18°</div>
<div style="display:flex;flex-direction:column;gap:2px">{icon("cloud",56,2.2)}<div style="font-size:22px;font-weight:700">Cloudy</div>
<div style="font-size:17px;font-weight:600">High 21° · low 12°</div><div style="font-size:17px;font-weight:600;display:flex;align-items:center;gap:4px">{icon("umbrella",20,2.4)} 20%</div></div></div>
<div class=sec style="padding-bottom:0">Today</div>{tcurve()}
<div class=sec>Next days</div>''' + "".join(
 f'<div class=row style="{"" if n == 0 else ""}min-height:54px;gap:14px"><b style="width:46px">{d}</b>{icon(i,30)}'
 f'<span style="margin-left:auto;font-size:18px;width:36px;text-align:right">{lo}°</span>{range_bar(lo, hi)}<b style="font-size:18px;width:36px">{hi}°</b></div>'
 for n, (d, i, lo, hi) in enumerate(DAYS)))

page3("settings-weather", H("Weather") + f'''<div class=sec>Location</div><div class=seg><div class=on>From phone</div><div>Fixed city</div></div>
<div style="height:16px"></div><div class=row>Now<span class=v>Lisbon · from phone</span></div>
<div class=sec>Units</div><div class=seg><div class=on>°C</div><div>°F</div></div>
<div style="padding:18px 24px;font-size:16px;font-weight:600">Set a fixed city in the phone app: Settings › Weather.</div>''')

BADGE = 'position:absolute;top:-10px;right:-10px;min-width:40px;height:40px;padding:0 8px;border-radius:20px;background:#000;color:#fff;border:3px solid #fff;display:flex;align-items:center;justify-content:center;font-size:19px;font-weight:700'
def LAUNCHER(status, rec):
    def tile(ic, name, on=False, badge="", inner=None):
        body = inner or icon(ic, 60, 2.2, "#fff" if on else "#000")
        b = f'<span style="{BADGE}">{badge}</span>' if badge else ""
        return f'<div class=app><div class="ic{" on" if on else ""}" style="position:relative">{body}{b}</div>{name}</div>'
    weather = f'<div style="display:flex;flex-direction:column;align-items:center;gap:0">{icon("cloud",44)}<b style="font-size:24px;line-height:1">18°</b></div>'
    return status + '<div class=apps style="padding-top:24px;gap:30px 0">' + "".join([
        tile("microphone" if rec else "microphone-off", "Recorder", on=rec),
        tile("list-check", "To-do", badge="11"),
        tile("file-text", "Transcripts", badge="14"),
        tile("", "Clock", inner='<b style="font-size:30px;letter-spacing:-1px">10:42</b>'),
        tile("chart-bar", "Stats"),
        tile("", "Weather", inner=weather),
        tile("settings", "Settings")]) + "</div>"
page3("launcher-a", LAUNCHER(STATUS, True))
page3("launcher-quiet", LAUNCHER(STATUS_MOON, True))
page3("launcher-sleep", LAUNCHER(STATUS_MOON, False))

EVENTS = [("11:00", "Billing review", "W"), ("13:30", "Lunch with Jo", "P"), ("15:00", "Product launch sync", "W"), ("18:30", "Gym", "P")]
page3("clock", H("Clock") + f'''
<div style="padding:22px 24px 0"><div style="font-size:120px;font-weight:700;letter-spacing:-5px;line-height:1">10:42</div>
<div style="font-size:24px;font-weight:600;margin-top:6px">Tuesday, 10 November</div></div>
<div class=sec style="padding-top:28px">Today</div>''' + "".join(
 f'<div class=row><b style="width:76px;font-size:20px">{t}</b>{n}</div>' for t, n, a in EVENTS)
 + '<div class=sec>Tomorrow</div><div class=row><b style="width:76px;font-size:20px">09:30</b>Stand-up</div>')
DEV = [("Firmware", "0.9.1 · up 3 d 4 h"), ("CPU", "240 MHz · 41 °C"), ("RAM", "62 KB free · lowest 41 KB"), ("PSRAM", "6.1 of 8 MB free"),
       ("Storage", "1.2 of 22 MB · 14 chunks wait"), ("Wi-Fi", "HomeWiFi · −58 dBm"), ("Bluetooth", "Pixel · 2M PHY"), ("Battery", "3.92 V · −48 mA")]
page3("stats-device", H("Stats") + "".join(f'<div class=row style="min-height:60px">{k}<span class=v>{v}</span></div>' for k, v in DEV) + PAGES("2 / 2"))
