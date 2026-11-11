const NS = 'http://www.w3.org/2000/svg', svg = document.getElementById('s'), rc = rough.svg(svg);
const add = n => svg.appendChild(n);
const pen = (w, s, r = 1.1) => ({roughness: r, strokeWidth: w, seed: s});
const g = (t) => { const e = document.createElementNS(NS, 'g'); e.setAttribute('transform', t); svg.appendChild(e); return e; };
const sun = (p, cx, cy, r, s) => { p.appendChild(rc.circle(cx, cy, r * 2, pen(3, s)));
  for (let k = 0; k < 10; k++) { const a = k * Math.PI / 5, c = Math.cos(a), n = Math.sin(a);
    p.appendChild(rc.line(cx + (r + 10) * c, cy + (r + 10) * n, cx + (r + 26) * c, cy + (r + 26) * n, pen(3, s + 1 + k))); } };
const cloud = (p, x, y, w, h, s) => p.appendChild(rc.path(`M${x} ${y + h} C${x - h * .6} ${y + h} ${x - h * .6} ${y + h * .35} ${x + w * .12} ${y + h * .4} C${x + w * .1} ${y - h * .15} ${x + w * .45} ${y - h * .25} ${x + w * .52} ${y + h * .15} C${x + w * .62} ${y - h * .05} ${x + w * .9} ${y + h * .05} ${x + w * .86} ${y + h * .45} C${x + w * 1.08} ${y + h * .45} ${x + w * 1.08} ${y + h} ${x + w * .9} ${y + h} Z`, {...pen(3, s), fill: '#fff', fillStyle: 'solid'}));
const drops = (p, x, y, n, s, snow) => { for (let k = 0; k < n; k++) { const dx = x + k * 26, dy = y + (k % 2) * 14;
  if (snow) { p.appendChild(rc.line(dx - 7, dy, dx + 7, dy, pen(2.6, s + k))); p.appendChild(rc.line(dx, dy - 7, dx, dy + 7, pen(2.6, s + 20 + k))); }
  else p.appendChild(rc.line(dx, dy, dx - 8, dy + 20, pen(3, s + k))); } };
const bolt = (p, x, y, s) => p.appendChild(rc.path(`M${x} ${y} L${x - 18} ${y + 36} L${x} ${y + 36} L${x - 12} ${y + 70}`, pen(3.4, s)));
const fog = (p, x, y, w, s) => { for (let k = 0; k < 3; k++) p.appendChild(rc.line(x + (k % 2) * 16, y + k * 22, x + w - (k % 2) * 10, y + k * 22, pen(3, s + k))); };
function cond(kind, cx, cy, sc, s) {
  const p = g(`translate(${cx},${cy}) scale(${sc}) translate(-100,-85)`);
  if (kind === 'sun') sun(p, 100, 85, 42, s);
  if (kind === 'cloud') { sun(p, 130, 60, 30, s); cloud(p, 30, 80, 130, 60, s + 30); }
  if (kind === 'rain') { cloud(p, 35, 40, 130, 62, s); drops(p, 62, 128, 4, s + 40); }
  if (kind === 'storm') { cloud(p, 35, 40, 130, 62, s); bolt(p, 112, 112, s + 50); drops(p, 56, 128, 2, s + 40); }
  if (kind === 'snow') { cloud(p, 35, 40, 130, 62, s); drops(p, 62, 132, 4, s + 40, true); }
  if (kind === 'fog') { cloud(p, 35, 26, 130, 62, s); fog(p, 30, 112, 150, s + 40); }
  if (sc < 0.5) p.querySelectorAll('path').forEach(e => { if (e.getAttribute('stroke-width')) e.setAttribute('stroke-width', 2.4 / sc); });
}
