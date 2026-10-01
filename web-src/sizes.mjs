import fs from 'fs'; import path from 'path';
import init, { HwpDocument } from '../research/rhwp/pkg-main/rhwp.js';
globalThis.measureTextWidth = (f, t) => t.length * 7;
await init({ module_or_path: fs.readFileSync('../research/rhwp/pkg-main/rhwp_bg.wasm') });
const dir = '../research/rhwp/samples';
for (const f of fs.readdirSync(dir)) {
  if (!/\.(hwp|hwpx)$/i.test(f)) continue;
  try { const d = new HwpDocument(new Uint8Array(fs.readFileSync(path.join(dir, f)))); const n = Math.min(d.pageCount(), 60); const set = new Set();
    for (let i = 0; i < n; i++) { const p = JSON.parse(d.getPageInfo(i)); set.add(p.width > p.height ? 'L' : 'P'); }
    if (set.size > 1) { console.log(f, d.pageCount()); }
  } catch {}
}
