import re
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

here = Path(__file__).parent
A = "../assets"
TODOS = ["Restore the test environment", "Check the time zone issue", "Meet Priya and Alex on Friday",
         "Set up two customer users", "Review the new dashboard", "Send Northwind the claims"]


def icon(name, size=32, sw=2.2, color="#000"):
    s = open(here / A / "icons" / f"{name}.svg").read()
    s = s.replace('width="24"', f'width="{size}"').replace('height="24"', f'height="{size}"')
    s = s.replace('stroke-width="2"', f'stroke-width="{sw}"').replace("currentColor", color)
    return re.sub(r'class="[^"]*"', "", s)


BASE = f"""@font-face{{font-family:M;src:url({A}/fonts/Montserrat.ttf)}}
@font-face{{font-family:H;src:url({A}/fonts/Caveat.ttf)}}
*{{box-sizing:border-box;margin:0;padding:0}}
body{{width:480px;height:800px;overflow:hidden;background:#fff;color:#000;font-family:M;font-weight:600;display:flex;flex-direction:column;position:relative}}
.status{{height:44px;display:flex;justify-content:space-between;align-items:center;padding:0 24px;font-size:18px}}
.status span{{display:flex;align-items:center;gap:6px}}
.row{{min-height:64px;display:flex;align-items:center;gap:16px;padding:0 24px;font-size:20px;border-bottom:2px solid #000}}
.row .v{{margin-left:auto;font-size:18px;font-weight:500;display:flex;align-items:center;gap:4px}}
.sub{{font-size:16px;font-weight:500}}
.sec{{font-size:16px;font-weight:700;letter-spacing:1px;text-transform:uppercase;padding:16px 24px 8px}}
.chk{{width:36px;height:36px;border:3px solid #000;border-radius:9px;flex:none}}
.btn{{height:64px;border:3px solid #000;border-radius:999px;display:flex;align-items:center;justify-content:center;font-size:20px;font-weight:700}}
.btn.on{{background:#000;color:#fff}}
.tabs{{margin-top:auto;height:72px;border-top:3px solid #000;display:flex}}
.tabs div{{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;font-size:14px;font-weight:700}}
.tabs .on{{background:#000;color:#fff}}
.hdr{{display:flex;align-items:center;gap:4px;padding:2px 16px 10px;font-size:30px;font-weight:700;border-bottom:2px solid #000}}
.hdr .p{{margin-left:auto;font-size:18px;font-weight:600}}
"""
STATUS = f'<div class=status><span>{icon("wifi",22,2.4)}</span><span>78% {icon("battery-3",28,2)}</span></div>'
ROUGH = f'<script src="{A}/js/rough.js"></script>'


def page(name, body, css="", script=""):
    (here / f"{name}.html").write_text(f"<!doctype html><meta charset=utf-8><style>{BASE}{css}</style><body>{body}{script}</body>")


def hdr(title, p=""):
    return f'<div class=hdr>{icon("chevron-left",32,2.4)}{title}' + (f"<span class=p>{p}</span>" if p else "") + "</div>"


def todo_rows(n, top=True):
    return "".join(f'<div class=row style="{"border-top:2px solid #000;" if top and i == 0 else ""}"><span class=chk></span>'
                   f'<span style="display:flex;flex-direction:column"><span>{t}</span><span class=sub>Product launch · 13:12</span></span></div>'
                   for i, t in enumerate(TODOS[:n]))


def settings_rows():
    return "".join(f'<div class=row>{icon(i,30)}{n}<span class=v>{v} {icon("chevron-right",22)}</span></div>'
                   for i, n, v in [("microphone", "Recording", "16 kbps"), ("wifi", "Wi-Fi", "HomeWiFi"),
                                   ("device-mobile-rotated", "Hub", "Pixel"), ("hand-finger", "Buttons", ""),
                                   ("device-mobile-rotated", "Device", "v0.9.1")])


# A: sketchbook. Same structure as now; hand-drawn lines, boxes and titles everywhere, like the splash.
SK_CSS = """.hdr{border-bottom:none;font-family:H;font-size:44px;padding:0 16px 4px}.hdr .p{font-family:H;font-size:28px}
.row{border-bottom:none}.sec{font-family:H;font-size:26px;text-transform:none;letter-spacing:0;padding:8px 24px 0}
.chk{border:none}.app{position:absolute;width:150px;text-align:center;font-family:H;font-size:30px}
.btn{border:none}"""
SK_JS = ROUGH + """<script>
const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('width',480);svg.setAttribute('height',800);
svg.style.cssText='position:absolute;left:0;top:0;pointer-events:none';document.body.appendChild(svg);const rc=rough.svg(svg);let seed=1;
const box=(e,o)=>{const r=e.getBoundingClientRect();return [r.left,r.top,r.width,r.height]};
document.querySelectorAll('.hdr').forEach(e=>{const [x,y,w,h]=box(e);svg.appendChild(rc.line(14,y+h,466,y+h+2,{roughness:1.2,strokeWidth:2.4,seed:seed++}))});
document.querySelectorAll('.row').forEach(e=>{const [x,y,w,h]=box(e);svg.appendChild(rc.line(20,y+h-1,460,y+h,{roughness:0.9,strokeWidth:1.8,seed:seed++}))});
document.querySelectorAll('.chk').forEach(e=>{const [x,y,w,h]=box(e);svg.appendChild(rc.rectangle(x+2,y+2,w-4,h-4,{roughness:1.1,strokeWidth:2.6,seed:seed++}))});
document.querySelectorAll('.tile').forEach(e=>{const [x,y,w,h]=box(e);svg.appendChild(rc.rectangle(x,y,w,h,{roughness:1.3,strokeWidth:2.8,seed:seed++,fill:e.dataset.on?'#000':undefined,fillStyle:'solid'}))});
document.querySelectorAll('.btn').forEach(e=>{const [x,y,w,h]=box(e);svg.appendChild(rc.rectangle(x,y,w,h,{roughness:1.2,strokeWidth:2.8,seed:seed++}))});
svg.querySelectorAll('path').forEach(p=>{if(p.getAttribute('fill')==='#000')p.parentNode.parentNode.insertBefore(p.parentNode,svg.firstChild)});
</script>"""
TILES = [(34, 84, "microphone", "Recorder", True), (180, 84, "list-check", "To-do", False),
         (326, 84, "chart-bar", "Stats", False), (34, 290, "settings", "Settings", False)]
page("a-launcher", STATUS + "".join(
    f'<div class=tile {"data-on=1" if on else ""} style="position:absolute;left:{x+10}px;top:{y}px;width:104px;height:104px;display:flex;align-items:center;justify-content:center;z-index:1">{icon(i,58,2.2,"#fff" if on else "#000")}</div>'
    f'<div class=app style="left:{x-13}px;top:{y+112}px">{n}</div>' for x, y, i, n, on in TILES)
    + '<div class=app style="left:21px;top:236px;font-size:22px">● rec</div><div class=app style="left:167px;top:236px;font-size:22px">11 open</div>',
    SK_CSS + ".tile svg{position:relative;z-index:2}", SK_JS)
page("a-todo", STATUS + hdr("to-do", "1 / 3") + '<div class=sec>open · 11</div>' + todo_rows(6, False), SK_CSS, SK_JS)
page("a-recorder", STATUS + hdr("recorder") + '''<div style="display:flex;align-items:center;padding:20px 24px">
<div><div style="font-family:H;font-size:44px;font-weight:700;line-height:1">● recording</div><div class=sub style="font-size:18px">2 h 14 today</div></div>
<div class=btn style="margin-left:auto;width:140px">Mute</div></div><div class=sec>last · 10:42 billing call</div>
<div style="padding:4px 24px;font-size:21px;line-height:1.45;font-weight:500">“…so we ship the billing report on Friday, and Sam checks the Q3 numbers first.”</div>''', SK_CSS, SK_JS)

# B: one home screen. No launcher: Home shows the recorder state, the next to-dos and the last conversation; four tabs.
TABS = [("home", "Home"), ("list-check", "To-do"), ("file-text", "Transcripts"), ("settings", "Settings")]


def tabs(on):
    return '<div class=tabs>' + "".join(f'<div class={"on" if n == on else ""}>{icon(i,30,2.2,"#fff" if n == on else "#000")}{n}</div>' for i, n in TABS) + "</div>"


page("b-home", STATUS + f'''<div style="display:flex;align-items:center;padding:8px 24px 18px;border-bottom:2px solid #000">
<div><div style="display:flex;align-items:center;gap:10px;font-size:30px;font-weight:700"><span style="width:16px;height:16px;border-radius:50%;background:#000"></span>Recording</div>
<div class=sub style="font-size:18px">2 h 14 today · 14 conversations</div></div>
<div class=btn style="margin-left:auto;width:130px">Mute</div></div>
<div class=sec>Next to-dos · 11</div>{todo_rows(3)}
<div class=sec>Last conversation</div>
<div class=row style="border-top:2px solid #000;flex-direction:column;align-items:flex-start;justify-content:center;gap:2px;min-height:84px">
<span>10:42 · Billing call</span><span class=sub>Ship the billing report on Friday; Sam checks the numbers.</span></div>''' + tabs("Home"))
page("b-todo", STATUS + '<div class=hdr style="padding-left:24px">To-do<span class=p>1 / 2</span></div><div class=sec>Open · 11</div>' + todo_rows(7) + tabs("To-do"))
page("b-settings", STATUS + '<div class=hdr style="padding-left:24px">Settings</div>' + settings_rows() + tabs("Settings"))

# C: live tiles. The launcher has four large tiles that show their content; detail pages stay as now.
TILE = "border:3px solid #000;border-radius:24px;padding:16px 18px;display:flex;flex-direction:column;gap:6px;overflow:hidden"
page("c-launcher", STATUS + f'''<div style="display:grid;grid-template-columns:1fr 1fr;grid-template-rows:1fr 1fr;gap:14px;padding:6px 18px 18px;flex:1">
<div style="{TILE};background:#000;color:#fff">{icon("microphone",40,2.2,"#fff")}<div style="font-size:26px;font-weight:700;margin-top:auto">Recording</div><div class=sub style="font-size:17px">2 h 14 today</div></div>
<div style="{TILE}">{icon("list-check",40)}<div style="font-size:44px;font-weight:700;line-height:1;margin-top:8px">11</div><div class=sub style="font-size:17px">open to-dos</div>
<div style="margin-top:auto;font-size:16px;font-weight:600;line-height:1.35">Restore the test environment<br>Check the time zone issue</div></div>
<div style="{TILE}">{icon("file-text",40)}<div style="font-size:22px;font-weight:700;margin-top:auto">Billing call</div><div class=sub style="font-size:17px">10:42 · 14 today</div></div>
<div style="{TILE}">{icon("settings",40)}<div style="margin-top:auto;font-size:17px;font-weight:600;line-height:1.5">HomeWiFi<br>Hub: Pixel<br>v0.9.1</div><div style="font-size:22px;font-weight:700">Settings</div></div>
</div>''')
page("c-todo", STATUS + hdr("To-do", "1 / 2") + '<div class=sec>Open · 11</div>' + todo_rows(7))
page("c-settings", STATUS + hdr("Settings") + settings_rows())

W, H = 480, 800
FONT = "/tmp/fontdl/Montserrat-SemiBold.ttf"
BOLD = "/tmp/fontdl/Montserrat-Bold.ttf"
GROUPS = [("A · Sketchbook: today's structure, drawn by hand like the splash", ["a-launcher", "a-recorder", "a-todo"]),
          ("B · One home screen: no launcher, four tabs at the bottom", ["b-home", "b-todo", "b-settings"]),
          ("C · Live tiles: four large tiles that show their content", ["c-launcher", "c-todo", "c-settings"])]
pad, gap = 28, 20
bw = pad * 2 + 3 * W + 2 * gap
board = Image.new("L", (bw, 90 + len(GROUPS) * (60 + H + gap)), 255)
d = ImageDraw.Draw(board)
d.text((pad, 22), "Sticky UI · three directions", font=ImageFont.truetype(BOLD, 40), fill=0)
y = 90
for title, names in GROUPS:
    d.text((pad, y), title, font=ImageFont.truetype(BOLD, 26), fill=0)
    y += 50
    for i, n in enumerate(names):
        out = f"/tmp/opt-{n}.png"
        subprocess.run(["google-chrome-stable", "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                        "--window-size=480,800", f"--screenshot={out}", f"file://{here / (n + '.html')}"], capture_output=True, check=True)
        im = Image.open(out).convert("L").crop((0, 0, W, H)).point(lambda v: 0 if v < 128 else 255)
        im.save(here / f"{n}.png")
        x = pad + i * (W + gap)
        board.paste(im, (x, y))
        d.rectangle((x - 2, y - 2, x + W + 1, y + H + 1), outline=170, width=2)
    y += H + gap + 10
board.save(here / "board-options.png")
print(sys.argv[0], "ok")
