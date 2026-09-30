// Render every page of one HWP/HWPX file to SVG; print one JSON line.
import fs from 'fs';
import init, { HwpDocument } from '../research/rhwp/pkg-main/rhwp.js';
globalThis.measureTextWidth = (font, text) => { const m = /(\d+(?:\.\d+)?)px/.exec(font); return (m ? +m[1] : 12) * 0.55 * [...text].length; };
const f = process.argv[2];
const out = { f, ok: false };
try {
  await init({ module_or_path: fs.readFileSync('../research/rhwp/pkg-main/rhwp_bg.wasm') });
  const t0 = Date.now();
  const doc = new HwpDocument(new Uint8Array(fs.readFileSync(f)));
  out.pages = doc.pageCount();
  out.loadMs = Date.now() - t0;
  let bad = 0;
  for (let i = 0; i < out.pages; i++) {
    try { const s = doc.renderPageSvg(i); if (!s || s.length < 100) bad++; } catch (e) { bad++; out.pageErr = String(e).slice(0, 200); }
  }
  out.badPages = bad;
  out.totalMs = Date.now() - t0;
  out.ok = out.pages > 0 && bad === 0;
} catch (e) { out.err = String(e).slice(0, 300); }
console.log(JSON.stringify(out));
