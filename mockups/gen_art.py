import json
import sys

root = sys.argv[1]



CSS = """@font-face{font-family:H;src:url(assets/fonts/Caveat.ttf)}@font-face{font-family:M;src:url(assets/fonts/Montserrat.ttf)}
*{margin:0;padding:0;box-sizing:border-box}body{width:480px;height:800px;overflow:hidden;background:#fff;position:relative;font-family:M}
.status{position:absolute;top:0;left:0;width:480px;height:44px;display:flex;justify-content:space-between;align-items:center;padding:0 24px;font-size:18px;font-weight:600}
.h{font-family:H;font-weight:700;position:absolute}"""


def status(title, icons):
    return (f'<div class=status><span style="display:flex;align-items:center;gap:10px">{icons["wifi"]}<b style="font-size:22px">{title}</b></span>'
            f'<span style="display:flex;align-items:center;gap:6px">78% {icons["battery-3"]}</span></div>')


def icon(name, size, sw=2.4):
    import re
    s = open(f"{root}/assets/icons/{name}.svg").read()
    s = s.replace('width="24"', f'width="{size}"').replace('height="24"', f'height="{size}"').replace('stroke-width="2"', f'stroke-width="{sw}"')
    return re.sub(r'class="[^"]*"', "", s.replace("currentColor", "#000"))


ICONS = {"wifi": icon("wifi", 22), "battery-3": icon("battery-3", 28, 2)}


def page(name, body, script):
    open(f"{root}/{name}.html", "w").write(
        f"<!doctype html><meta charset=utf-8><style>{CSS}</style><body>{body}<svg id=s width=480 height=800 style='position:absolute;left:0;top:0'></svg>"
        f"<script src='assets/js/rough.js'></script><script src='assets/js/art.js'></script><script>{script}</script></body>")


EVENTS = [("11:00", "Billing review"), ("13:30", "Lunch with Jo"), ("15:00", "Product launch sync"), ("18:30", "Gym")]
TEXT = """
const text = (x, y, t, size, anchor = 'middle', weight = 700) => { const e = document.createElementNS(NS, 'text'); e.setAttribute('x', x); e.setAttribute('y', y);
  e.setAttribute('text-anchor', anchor); e.setAttribute('font-family', 'H'); e.setAttribute('font-weight', weight); e.setAttribute('font-size', size); e.textContent = t; svg.appendChild(e); };
const agenda = (top) => { text(24, top, 'today', 32, 'start');
  EVENTS.forEach(([t, n], i) => { const y = top + 44 + i * 50; add(rc.circle(36, y - 7, 10, {...pen(2, 70 + i), fill: '#000', fillStyle: 'solid'}));
    text(56, y, t, 28, 'start'); text(140, y, n, 28, 'start', 500); }); };
"""


def clock(name, art):
    page(name, status("Clock", ICONS), "const EVENTS = " + json.dumps(EVENTS) + ";" + TEXT + art)


clock("clock", """
const cx = 240, cy = 226, r = 150;
add(rc.circle(cx, cy, r * 2, pen(3.4, 1, 1.4)));
add(rc.circle(cx, cy, r * 2 - 18, pen(1.6, 2, 1.6)));
for (let k = 0; k < 60; k++) { if (k % 5) { const a = k * Math.PI / 30; add(rc.circle(cx + (r - 20) * Math.cos(a), cy + (r - 20) * Math.sin(a), 3, {...pen(1, 100 + k), fill: '#000', fillStyle: 'solid'})); } }
for (let k = 1; k <= 12; k++) { const a = (k * 30 - 90) * Math.PI / 180; text(cx + (r - 46) * Math.cos(a), cy + (r - 46) * Math.sin(a) + 13, k, k % 3 ? 30 : 40); }
const hand = (ang, len, w, s) => { const a = (ang - 90) * Math.PI / 180, x = cx + len * Math.cos(a), y = cy + len * Math.sin(a), nx = -Math.sin(a) * w, ny = Math.cos(a) * w;
  add(rc.polygon([[cx + nx, cy + ny], [x, y], [cx - nx, cy - ny], [cx - 0.25 * len * Math.cos(a), cy - 0.25 * len * Math.sin(a)]], {...pen(2, s), fill: '#000', fillStyle: 'solid'})); };
hand((10 + 42 / 60) * 30, 78, 9, 3); hand(42 * 6, 120, 6, 4);
add(rc.circle(cx, cy, 16, {...pen(2, 5), fill: '#fff', fillStyle: 'solid'}));
text(240, 432, 'tuesday, 10 november', 40);
agenda(492);
""")
clock("clock-digits", """
text(250, 262, "10:42", 200);
add(rc.line(60, 282, 420, 278, pen(3, 1, 1.6)));
add(rc.line(80, 294, 400, 292, pen(2, 2, 1.8)));
const sx = 60, sy = 84; add(rc.circle(sx, sy, 40, pen(2.6, 3)));
for (let k = 0; k < 8; k++) { const a = k * Math.PI / 4; add(rc.line(sx + 28 * Math.cos(a), sy + 28 * Math.sin(a), sx + 38 * Math.cos(a), sy + 38 * Math.sin(a), pen(2.4, 10 + k))); }
text(240, 350, 'tuesday, 10 november', 40);
agenda(430);
""")
clock("clock-words", """
text(240, 140, "it's about", 52, 'middle', 600);
text(240, 250, 'quarter', 120);
text(240, 360, 'to eleven', 120);
add(rc.path('M70 384 C160 404 320 372 410 392', pen(3, 1, 1.4)));
text(240, 440, 'tuesday, 10 november', 36, 'middle', 600);
agenda(500);
""")
clock("clock-note", """
const n = g('rotate(-3 240 220)');
n.appendChild(rc.path('M70 90 L420 90 L420 330 L380 370 L70 370 Z', {roughness: 0.8, stroke: 'none', fill: '#000', fillStyle: 'hachure', hachureGap: 6, fillWeight: 1.6, hachureAngle: 40, seed: 2}));
n.appendChild(rc.path('M58 78 L408 78 L408 318 L368 358 L58 358 Z', {...pen(3, 1), fill: '#fff', fillStyle: 'solid'}));
n.appendChild(rc.path('M408 318 L368 318 L368 358', pen(2.6, 3)));
n.appendChild(rc.polygon([[182, 58], [292, 62], [290, 98], [180, 94]], {...pen(2.4, 5), fill: '#000', fillStyle: 'hachure', hachureGap: 4, fillWeight: 1.4, hachureAngle: -60}));
const t = (x, y, s, size) => { const e = document.createElementNS(NS, 'text'); e.setAttribute('x', x); e.setAttribute('y', y); e.setAttribute('text-anchor', 'middle');
  e.setAttribute('font-family', 'H'); e.setAttribute('font-weight', 700); e.setAttribute('font-size', size); e.textContent = s; n.appendChild(e); };
t(232, 250, '10:42', 150); t(232, 316, 'tue, 10 nov', 38);
agenda(440);
""")

clock("clock-flip", """
const card = (x, digit, s) => { const w = 100;
  add(rc.rectangle(x + 7, 82, w, 248, {roughness: 0.8, stroke: 'none', fill: '#000', fillStyle: 'hachure', hachureGap: 6, fillWeight: 1.6, hachureAngle: 40, seed: s}));
  add(rc.path(`M${x + 18} 70 H${x + w - 18} Q${x + w} 70 ${x + w} 88 V300 Q${x + w} 318 ${x + w - 18} 318 H${x + 18} Q${x} 318 ${x} 300 V88 Q${x} 70 ${x + 18} 70 Z`, {...pen(3.2, s + 1), fill: '#fff', fillStyle: 'solid'}));
  text(x + w / 2 - 8, 240, digit, 170);
  add(rc.line(x - 3, 194, x + w + 3, 194, pen(3, s + 2, 0.6)));
};
card(22, '1', 10); card(130, '0', 20); card(250, '4', 30); card(358, '2', 40);
add(rc.circle(240, 164, 10, {...pen(2, 50), fill: '#000', fillStyle: 'solid'})); add(rc.circle(240, 224, 10, {...pen(2, 51), fill: '#000', fillStyle: 'solid'}));
text(240, 392, 'tuesday, 10 november', 40);
agenda(452);
""")

HOURS = [18, 19, 20, 21, 21, 21, 20, 19, 17, 16, 15, 14, 14]
DAYS = [("tue", "rain", 11, 17), ("wed", "cloud", 12, 19), ("thu", "sun", 13, 22), ("fri", "storm", 12, 18), ("sat", "snow", -2, 3)]
page("weather", status("Weather", ICONS)
     + '<div class=h style="top:60px;left:250px;font-size:128px;line-height:1">18°</div>'
     + '<div class=h style="top:206px;left:254px;font-size:32px">cloudy · feels 16°</div>'
     + '<div class=h style="top:244px;left:254px;font-size:28px">21° / 12° · rain 20%</div>'
     + "".join(f'<div class=h style="top:{548 + i * 50}px;left:30px;font-size:34px">{d}</div><div class=h style="top:{548 + i * 50}px;left:204px;font-size:30px;width:50px;text-align:right">{lo}°</div>'
               f'<div class=h style="top:{548 + i * 50}px;left:410px;font-size:30px">{hi}°</div>' for i, (d, _, lo, hi) in enumerate(DAYS)),
     f"""
cond('cloud', 130, 170, 1.15, 100);
const temps = {json.dumps(HOURS)}, x = k => 44 + k * 33, y = t => 452 - (t - 14) * 14;
add(rc.curve(temps.map((t, k) => [x(k), y(t)]), pen(3.2, 200, 0.8)));
[0, 3, 6, 9, 12].forEach((k, i) => {{ add(rc.circle(x(k), y(temps[k]), 12, {{...pen(2, 210 + i), fill: '#000', fillStyle: 'solid'}}));
  const e = document.createElementNS(NS, 'text'); e.setAttribute('x', x(k)); e.setAttribute('y', y(temps[k]) - 16); e.setAttribute('text-anchor', 'middle');
  e.setAttribute('font-family', 'H'); e.setAttribute('font-weight', 700); e.setAttribute('font-size', 28); e.textContent = temps[k] + '°'; svg.appendChild(e);
  const l = document.createElementNS(NS, 'text'); l.setAttribute('x', x(k)); l.setAttribute('y', 512); l.setAttribute('text-anchor', 'middle');
  l.setAttribute('font-family', 'H'); l.setAttribute('font-size', 24); l.textContent = ['now', '14:00', '17:00', '20:00', '23:00'][i]; svg.appendChild(l); }});
{json.dumps([d[1] for d in DAYS])}.forEach((c, i) => cond(c, 140, 570 + i * 50, 0.26, 300 + i * 40));
{json.dumps([[d[2], d[3]] for d in DAYS])}.forEach(([lo, hi], i) => {{ const a = 262 + (lo + 4) * 5, b = 262 + (hi + 4) * 5, yy = 572 + i * 50;
  add(rc.line(262, yy, 400, yy, pen(1.6, 400 + i))); add(rc.rectangle(a, yy - 6, b - a, 12, {{...pen(2, 420 + i), fill: '#000', fillStyle: 'solid'}})); }});
""")


WTEXT = """
const text = (x, y, t, size, anchor = 'middle', weight = 700) => { const e = document.createElementNS(NS, 'text'); e.setAttribute('x', x); e.setAttribute('y', y);
  e.setAttribute('text-anchor', anchor); e.setAttribute('font-family', 'H'); e.setAttribute('font-weight', weight); e.setAttribute('font-size', size); e.textContent = t; svg.appendChild(e); };
"""
D5 = json.dumps([[d[0], d[1], d[2], d[3]] for d in DAYS])
page("weather-big", status("Weather", ICONS), WTEXT + f"""
cond('cloud', 240, 220, 2.0, 100);
text(240, 500, '18°', 150); text(240, 560, 'cloudy · feels 16° · rain 20%', 32, 'middle', 600);
{D5}.forEach(([d, c, lo, hi], i) => {{ const x = 48 + i * 96; text(x, 640, d, 30); cond(c, x, 690, 0.3, 300 + i * 40); text(x, 762, hi + '° ' + lo + '°', 24, 'middle', 600); }});
""")
page("weather-journal", status("Weather", ICONS), WTEXT + f"""
for (let k = 0; k < 13; k++) add(rc.line(20, 118 + k * 52, 460, 118 + k * 52, pen(1, 500 + k, 0.6)));
add(rc.line(70, 60, 70, 790, pen(1.6, 520, 0.8)));
text(84, 108, 'tuesday, 10 november', 40, 'start');
text(84, 160, 'cloudy, 18°.', 40, 'start'); text(84, 212, 'feels like 16°.', 36, 'start', 600);
text(84, 264, 'high 21°, low 12°.', 36, 'start', 600); text(84, 316, '20% chance of rain.', 36, 'start', 600);
text(84, 368, 'cooling to 14° by tonight.', 36, 'start', 600);
text(84, 476, 'this week', 36, 'start');
{D5}.forEach(([d, c, lo, hi], i) => {{ text(84, 528 + i * 52, d, 32, 'start'); cond(c, 170, 514 + i * 52, 0.22, 300 + i * 40); text(214, 528 + i * 52, c + ', ' + lo + '° to ' + hi + '°', 30, 'start', 600); }});
""")
page("weather-week", status("Weather", ICONS), WTEXT + f"""
cond('cloud', 96, 120, 0.7, 100); text(186, 150, '18°', 96, 'start'); text(404, 112, 'cloudy', 32); text(404, 150, '21° / 12°', 28, 'middle', 600);
{D5}.forEach(([d, c, lo, hi], i) => {{ const x = 14 + (i % 2) * 230, y = 228 + Math.floor(i / 2) * 186, w = i === 4 ? 452 : 222;
  add(rc.rectangle(x, y, w, 172, pen(2.6, 600 + i, 1.2))); text(x + 20, y + 44, d, 40, 'start');
  cond(c, x + w - 64, y + 58, 0.5, 300 + i * 40); text(x + 20, y + 140, hi + '°', 64, 'start'); text(x + 112, y + 140, lo + '°', 40, 'start', 600); }});
""")
