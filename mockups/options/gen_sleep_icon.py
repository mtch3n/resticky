import sys

OPTIONS = [
    ("a", "Moon and z z z", """
c.appendChild(rc.path('M18 4 C7 7 3 21 10 30 C17 38 31 37 36 28 C25 31 15 22 18 4 Z', {...pen(2.4, 3), fill: '#000', fillStyle: 'solid'}));
z(c, 31, 12, 12, 1); z(c, 39, 3, 9, 2);
"""),
    ("b", "Sleepy sticky note", """
c.appendChild(rc.path('M3 4 L35 3 L36 30 L29 38 L4 37 Z', {...pen(2.4, 11), fill: '#fff', fillStyle: 'solid'}));
c.appendChild(rc.path('M36 30 L29 30 L29 38', pen(2, 12)));
c.appendChild(rc.path('M8 19 Q12.5 25 17 19', pen(2.6, 13)));
c.appendChild(rc.path('M21 19 Q25.5 25 30 19', pen(2.6, 14)));
z(c, 38, 4, 10, 4);
"""),
    ("c", "Cloud nap", """
c.appendChild(rc.path('M8 34 C1 34 1 23 9 23 C9 13 21 10 24 18 C27 10 39 13 37 23 C44 23 44 34 37 34 Z', {...pen(2.4, 21), fill: '#fff', fillStyle: 'solid'}));
c.appendChild(rc.path('M13 27 Q16.5 31 20 27', pen(2.4, 22)));
c.appendChild(rc.path('M25 27 Q28.5 31 32 27', pen(2.4, 23)));
z(c, 38, 2, 10, 6);
"""),
]

TEXTS = [("Sleeping · press to wake", "Side button: light sleep"), ("Deep sleep · press to wake", "Deep sleep"), ("Night · wakes at 07:00", "Night window")]


def page(root):
    cards = []
    for key, name, js in OPTIONS:
        for k, (text, label) in enumerate(TEXTS):
            cards.append((key, name, js, text, label, k)) if k == 0 or True else None
    html = ["""<!doctype html><meta charset=utf-8><style>
@font-face{font-family:H;src:url(../assets/fonts/Caveat.ttf)}
body{margin:0;background:#ddd;font-family:Montserrat,sans-serif}
.grid{display:grid;grid-template-columns:repeat(3,500px);gap:24px;padding:24px}
.cap{font:600 18px sans-serif;margin:0 0 8px}
.dev{width:480px;height:520px;background:#fff;position:relative;overflow:hidden;border:2px solid #999}
.bg{position:absolute;left:0;top:0;width:480px;filter:grayscale(1)}
.status{position:absolute;left:0;top:0;width:480px;height:52px;background:#fff;display:flex;align-items:center;gap:10px;padding:0 24px;box-sizing:border-box;font:700 20px sans-serif}
.status .bat{margin-left:auto;font:600 18px sans-serif}
.zoom{width:200px;height:160px;background:#fff;border:2px solid #999;margin-top:10px}
</style><body><div class=grid>"""]
    for i, (key, name, js, text, label, k) in enumerate(cards):
        html.append(f"""<div><p class=cap>{key.upper()} · {name} — {label}</p>
<div class=dev><img class=bg src="../launcher-a.png">
<div class=status><svg id=s{i} width=52 height=44 viewBox="0 0 50 42"></svg><span>{text}</span><span class=bat>78%</span></div></div>
{'<svg class=zoom id=z'+str(i)+' viewBox="0 0 50 40"></svg>' if k == 0 else ''}</div>""")
    html.append("""</div><script src="../assets/js/rough.js"></script><script>
const NS='http://www.w3.org/2000/svg';
const pen=(w,s)=>({roughness:0.9,strokeWidth:w,seed:s,bowing:0.8});
function z(c,x,y,size,seed){const t=document.createElementNS(NS,'text');t.setAttribute('x',x);t.setAttribute('y',y+size);t.setAttribute('font-family','H');t.setAttribute('font-weight','700');t.setAttribute('font-size',size*1.5);t.textContent='z';c.appendChild(t);}
const draws={""" + ",".join(f"'{k}':(c,rc)=>{{{js}}}" for k, _, js in OPTIONS) + """};
document.querySelectorAll('svg[id]').forEach(svg=>{const i=+svg.id.slice(1);const key='""" + "".join(c[0] for c in cards) + """'[i];const rc=rough.svg(svg);const g=document.createElementNS(NS,'g');svg.appendChild(g);draws[key](g,rc);});
</script></body>""")
    open(f"{root}/options/sleep-icon.html", "w").write("\n".join(html))


page(sys.argv[1])
