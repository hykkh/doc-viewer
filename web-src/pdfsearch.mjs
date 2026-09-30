import fs from 'fs';
const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
const doc = await pdfjs.getDocument({ data: new Uint8Array(fs.readFileSync(process.argv[2])), cMapUrl: 'node_modules/pdfjs-dist/cmaps/', cMapPacked: true }).promise;
const needle = process.argv[3].toLowerCase(); let total = 0, first = null;
for (let i = 0; i < doc.numPages; i++) {
  const page = await doc.getPage(i + 1); const vp = page.getViewport({ scale: 1 }); const tc = await page.getTextContent();
  let str = ''; const spans = [];
  for (const it of tc.items) { if (!it.str) continue; spans.push({ start: str.length, end: str.length + it.str.length, it }); str += it.str; if (it.hasEOL) str += ' '; }
  str = str.toLowerCase(); let at = str.indexOf(needle);
  while (at >= 0) { const end = at + needle.length;
    for (const s of spans) { if (s.end <= at || s.start >= end) continue; const it = s.it; const a = Math.max(at, s.start) - s.start, b = Math.min(end, s.end) - s.start;
      const [, , , , x0, y0] = it.transform; const fontH = it.height || Math.hypot(it.transform[2], it.transform[3]);
      const x1 = x0 + it.width * (a / it.str.length), x2 = x0 + it.width * (b / it.str.length);
      const [l, t] = vp.convertToViewportPoint(x1, y0 - fontH * 0.2); const [r, bt] = vp.convertToViewportPoint(x2, y0 + fontH * 0.9);
      if (!first) first = { page: i, x: Math.min(l, r), y: Math.min(t, bt), w: Math.abs(r - l), h: Math.abs(bt - t) }; }
    total++; at = str.indexOf(needle, end); }
}
console.log('hits', total, 'first', JSON.stringify(first));
