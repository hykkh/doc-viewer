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
