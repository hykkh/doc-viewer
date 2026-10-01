// Parses and lays out HWP/HWPX off the main thread (a 300-page file can take
// seconds), and renders page SVGs on request.
import init, { HwpDocument } from './rhwp/rhwp.js';

let ctx = null, lastFont = '';
globalThis.measureTextWidth = (font, text) => {
  if (!ctx) ctx = new OffscreenCanvas(1, 1).getContext('2d');
  if (font !== lastFont) { ctx.font = font; lastFont = font; }
  return ctx.measureText(text).width;
};

let doc = null;
let bytes = null;
const ready = init({ module_or_path: './rhwp/rhwp_bg.wasm' });

// Matches as page rectangles in page units: [{ page, rects: [{x,y,w,h}] }].
function search(q) {
  const matches = JSON.parse(doc.searchAllText(q, false, true)).slice(0, 1000);
  const hits = [];
  for (const mt of matches) {
    try {
      const end = mt.charOffset + mt.length;
      const c = mt.cellContext;
      if (c) {
        const a = JSON.parse(doc.getCursorRectInCell(mt.sec, c.parentPara, c.ctrlIdx, c.cellIdx, c.cellPara, mt.charOffset));
        const b = JSON.parse(doc.getCursorRectInCell(mt.sec, c.parentPara, c.ctrlIdx, c.cellIdx, c.cellPara, end));
        const w = b.pageIndex === a.pageIndex && b.y === a.y ? b.x - a.x : 12 * mt.length;
        hits.push({ page: a.pageIndex, rects: [{ x: a.x, y: a.y, w: Math.max(w, 6), h: a.height }] });
      } else {
        const rs = JSON.parse(doc.getSelectionRects(mt.sec, mt.para, mt.charOffset, mt.para, end));
        if (rs.length) {
          hits.push({ page: rs[0].pageIndex, rects: rs.filter((r) => r.pageIndex === rs[0].pageIndex).map((r) => ({ x: r.x, y: r.y, w: r.width, h: r.height })) });
        }
      }
    } catch { /* match inside an object we cannot locate: skip it */ }
  }
  return hits;
}

// A page's text as drawn, line by line. Unlike getPageText this includes
// table cells and text boxes, which is where much of a form's text lives.
function layoutText(p) {
  const runs = (JSON.parse(doc.getPageTextLayout(p)).runs || []).filter((r) => r.text);
  const lines = [];
  for (const r of runs.sort((a, b) => a.y - b.y || a.x - b.x)) {
    const l = lines.find((o) => Math.abs(o.y - r.y) < Math.max(2, r.h * 0.4));
    if (l) l.runs.push(r); else lines.push({ y: r.y, runs: [r] });
  }
  return lines.sort((a, b) => a.y - b.y).map((l) => {
    let s = '', end = -Infinity;
    for (const r of l.runs.sort((a, b) => a.x - b.x)) {
      if (s && r.x - end > (r.fontSize || 10) * 0.25) s += ' ';
      s += r.text;
      end = r.x + r.w;
    }
    return s;
  }).join('\n');
}

// Many documents have no outline numbering; take lines set clearly larger than
// the body text as headings instead (larger = higher level).
function headingsBySize() {
  const lines = [];
  const chars = new Map(); // font size → characters set in it
  for (let p = 0; p < doc.pageCount(); p++) {
    const layout = JSON.parse(doc.getPageTextLayout(p));
    const byY = new Map();
    for (const r of layout.runs || []) {
      if (!r.text || !r.text.trim()) continue;
      const k = Math.round(r.y);
      const o = byY.get(k) || { y: r.y, text: '', size: 0 };
      o.text += r.text;
      o.size = Math.max(o.size, r.fontSize);
      byY.set(k, o);
    }
    for (const o of [...byY.values()].sort((a, b) => a.y - b.y)) {
      lines.push({ ...o, page: p });
      const s = Math.round(o.size);
      chars.set(s, (chars.get(s) || 0) + o.text.length);
    }
  }
  let body = 0, most = 0;
  for (const [s, n] of chars) if (n > most) { most = n; body = s; }
  const heads = [];
  for (const l of lines) {
    const t = l.text.trim();
    if (l.size < body * 1.35 || t.length > 60 || (t.match(/[가-힣A-Za-z]/g) || []).length < 2) continue;
    const prev = heads[heads.length - 1];
    // A title broken over two lines on the same page: join it.
    if (prev && prev.page === l.page && Math.abs(prev.size - l.size) < 0.5 && l.y - prev.y < l.size * 2.2) {
      prev.title += ' ' + t;
      prev.y = l.y;
      continue;
    }
    heads.push({ title: t, page: l.page, size: l.size, y: l.y });
  }
  const levels = [...new Set(heads.map((h) => Math.round(h.size)))].sort((a, b) => b - a);
  return heads.slice(0, 300).map((h) => ({ title: h.title, page: h.page, depth: Math.min(2, levels.indexOf(Math.round(h.size))) }));
}

self.onmessage = async (ev) => {
  const m = ev.data;
  try {
    await ready;
    if (m.type === 'open') {
      if (!bytes) {
        const r = await fetch(m.src);
        if (!r.ok) throw new Error('파일 읽기 실패 ' + r.status);
        bytes = new Uint8Array(await r.arrayBuffer());
      }
      doc = m.password != null
        ? HwpDocument.openWithPassword(bytes, m.password)
        : new HwpDocument(bytes);
      const n = doc.pageCount();
      const sizes = [];
      for (let i = 0; i < n; i++) {
        const info = JSON.parse(doc.getPageInfo(i));
        sizes.push({ w: info.width || 794, h: info.height || 1123 });
      }
      self.postMessage({ type: 'opened', sizes });
    } else if (m.type === 'render') {
      self.postMessage({ type: 'page', id: m.id, svg: doc.renderPageSvg(m.i) });
    } else if (m.type === 'search') {
      self.postMessage({ type: 'found', id: m.id, hits: search(m.q) });
    } else if (m.type === 'text') {
      let t = layoutText(m.i);
      if (!t.trim()) {
        // getPageText returns a JSON string literal.
        t = doc.getPageText(m.i);
        try { t = JSON.parse(t); } catch { /* already plain */ }
      }
      self.postMessage({ type: 'text', id: m.id, text: String(t).replace(/\r\n/g, '\n') });
    } else if (m.type === 'outline') {
      const nav = JSON.parse(doc.getOutlineNavigation());
      let items = (nav.outline || [])
        .filter((o) => o.page > 0 && o.title)
        .map((o) => ({ title: `${o.number ? o.number + ' ' : ''}${o.title}`, page: o.page - 1, depth: Math.max(0, (o.level || 1) - 1) }));
      if (!items.length) items = headingsBySize();
      self.postMessage({ type: 'outline', id: m.id, items });
    }
  } catch (e) {
    const message = (e && e.message) ? e.message : String(e);
    // Replies to render/search carry the request id; only 'open' has none.
    self.postMessage({
      type: 'error',
      id: m.id,
      message,
      needPassword: m.type === 'open' && /비밀번호|password/i.test(message),
    });
  }
};
