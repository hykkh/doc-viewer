import fs from 'fs';
import init, { HwpDocument } from '../research/rhwp/pkg-main/rhwp.js';
globalThis.measureTextWidth = (f, t) => t.length * 7;
await init({ module_or_path: fs.readFileSync('../research/rhwp/pkg-main/rhwp_bg.wasm') });
const d = new HwpDocument(new Uint8Array(fs.readFileSync(process.argv[2])));
const t0 = Date.now();
const lines = []; const sizes = new Map();
for (let p = 0; p < d.pageCount(); p++) {
  const L = JSON.parse(d.getPageTextLayout(p));
  // merge runs on the same baseline into lines
  const byY = new Map();
  for (const r of L.runs) { if (!r.text || !r.text.trim()) continue; const k = Math.round(r.y); const o = byY.get(k) || { y: r.y, text: '', size: 0, bold: false }; o.text += r.text; o.size = Math.max(o.size, r.fontSize); o.bold ||= r.bold; byY.set(k, o); }
  for (const o of byY.values()) { lines.push({ ...o, page: p }); sizes.set(Math.round(o.size), (sizes.get(Math.round(o.size)) || 0) + o.text.length); }
}
let body = 0, best = 0; for (const [s, n] of sizes) if (n > best) { best = n; body = s; }
const heads = lines.filter(l => l.size >= body * 1.35 && l.text.trim().length >= 2 && l.text.trim().length <= 60);
console.log('ms', Date.now() - t0, 'pages', d.pageCount(), 'body', body, 'heads', heads.length);
for (const h of heads.slice(0, 40)) console.log(h.page + 1, h.size, h.text.trim().slice(0, 50));
