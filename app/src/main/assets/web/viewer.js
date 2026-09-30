// Document renderer inside the app's WebView.
// Query: kind = pdf | hwp | image | text, src = URL of the document bytes.
// Reports back through window.Android (see ViewerActivity.JsBridge).

const params = new URLSearchParams(location.search);
const kind = params.get('kind');
const src = params.get('src');

const bridge = window.Android || {
  onReady: (n) => console.log('ready', n),
  onFail: (m) => console.error('fail', m),
};

const msgEl = document.getElementById('msg');
const pagesEl = document.getElementById('pages');
const pageNumEl = document.getElementById('pageNum');

// Password prompt in the app's own dialog (window.prompt would show the page URL).
function askPassword(message) {
  if (!bridge.askPassword) return Promise.resolve(prompt(message));
  return new Promise((resolve) => {
    window.__pwResolve = (pw) => { window.__pwResolve = null; resolve(pw); };
    bridge.askPassword(message);
  });
}

function fail(e) {
  const m = (e && e.message) ? e.message : String(e);
  msgEl.style.display = 'block';
  msgEl.textContent = '열 수 없습니다: ' + m;
  bridge.onFail(m);
}

async function fetchBytes() {
  const r = await fetch(src);
  if (!r.ok) throw new Error('파일 읽기 실패 ' + r.status);
  return new Uint8Array(await r.arrayBuffer());
}

function pageWidthCss() {
  return Math.min(document.documentElement.clientWidth - 12, 1000);
}

// ---- lazy page machinery shared by pdf and hwp ------------------------------
// Each page gets a sized placeholder; content is drawn when it nears the
// viewport and dropped again when far away, so 500-page files stay light.

const KEEP = 4; // pages kept alive on each side of the visible one

// Search highlights per page, in page units: Map<page, [{x,y,w,h,current}]>.
const highlights = new Map();

let pages = null; // set by setupPages

function setupPages(sizes, draw, release) {
  const w = pageWidthCss();
  const slots = sizes.map((s, i) => {
    const d = document.createElement('div');
    d.className = 'page';
    d.style.width = w + 'px';
    d.style.height = Math.round(w * s.h / s.w) + 'px';
    d.dataset.i = i;
    d.innerHTML = `<div class="ph">${i + 1}</div>`;
    pagesEl.appendChild(d);
    return { el: d, drawn: false, busy: false };
  });

  let current = 0;
  const visible = new Set();

  function decorate(i) {
    const el = slots[i].el;
    el.querySelectorAll('.hl').forEach((h) => h.remove());
    const hs = highlights.get(i);
    if (!hs) return;
    const k = el.clientWidth / sizes[i].w;
    for (const r of hs) {
      const d = document.createElement('div');
      d.className = r.current ? 'hl cur' : 'hl';
      d.style.left = (r.x * k - 1) + 'px';
      d.style.top = (r.y * k - 1) + 'px';
      d.style.width = (r.w * k + 2) + 'px';
      d.style.height = (r.h * k + 2) + 'px';
      el.appendChild(d);
    }
  }

  async function ensure(i) {
    const s = slots[i];
    if (!s || s.drawn || s.busy) return;
    s.busy = true;
    try {
      await draw(i, s.el);
      s.drawn = true;
      decorate(i);
    } catch (e) {
      s.el.innerHTML = `<div class="ph">${i + 1}쪽 표시 오류</div>`;
      console.error(e);
    } finally {
      s.busy = false;
    }
  }

  function trim() {
    slots.forEach((s, i) => {
      if (s.drawn && Math.abs(i - current) > KEEP * 2) {
        release(i, s.el);
        s.el.innerHTML = `<div class="ph">${i + 1}</div>`;
        s.drawn = false;
      }
    });
  }

  const io = new IntersectionObserver((entries) => {
    for (const en of entries) {
      const i = Number(en.target.dataset.i);
      if (en.isIntersecting) visible.add(i); else visible.delete(i);
    }
    if (visible.size) {
      current = Math.min(...visible);
      pageNumEl.textContent = `${current + 1} / ${slots.length}`;
      for (let i = current - 1; i <= current + KEEP; i++) ensure(i);
      trim();
    }
  }, { rootMargin: '100% 0px' });
  slots.forEach((s) => io.observe(s.el));

  pageNumEl.style.display = 'block';
  pageNumEl.textContent = `1 / ${slots.length}`;
  ensure(0);

  pages = {
    count: slots.length,
    // Scroll so page i (and optionally y in page units) sits near the top.
    scrollTo(i, y) {
      const s = slots[Math.max(0, Math.min(i, slots.length - 1))];
      const k = s.el.clientWidth / sizes[i].w;
      const top = s.el.getBoundingClientRect().top + window.scrollY + (y ? y * k - 120 : -8);
      window.scrollTo({ top: Math.max(0, top) });
    },
    redecorate() {
      slots.forEach((s, i) => { if (s.drawn) decorate(i); });
    },
  };
}

pageNumEl.addEventListener('click', () => {
  if (!pages) return;
  const v = prompt(`이동할 쪽 (1~${pages.count})`);
  const n = parseInt(v, 10);
  if (n >= 1 && n <= pages.count) pages.scrollTo(n - 1);
});

// ---- search ------------------------------------------------------------------
// Each opener sets `searcher` to async (query) → [{ page, rects: [{x,y,w,h}] }].

let searcher = null;
let hits = [];
let hitIdx = -1;

const bar = document.getElementById('search');
const qEl = document.getElementById('q');
const countEl = document.getElementById('count');

function showHit(idx) {
  highlights.clear();
  hits.forEach((h, n) => {
    const list = highlights.get(h.page) || [];
    h.rects.forEach((r) => list.push({ ...r, current: n === idx }));
    highlights.set(h.page, list);
  });
  hitIdx = idx;
  countEl.textContent = hits.length ? `${idx + 1} / ${hits.length}` : '없음';
  pages.redecorate();
  if (hits[idx]) pages.scrollTo(hits[idx].page, hits[idx].rects[0].y);
}

async function runSearch() {
  const q = qEl.value.trim();
  if (!q || !searcher || !pages) return;
  countEl.textContent = '찾는 중…';
  try {
    hits = await searcher(q);
  } catch (e) {
    hits = [];
    console.error(e);
  }
  showHit(hits.length ? 0 : -1);
}

qEl.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { qEl.blur(); runSearch(); }
});
document.getElementById('next').onclick = () => { if (hits.length) showHit((hitIdx + 1) % hits.length); };
document.getElementById('prev').onclick = () => { if (hits.length) showHit((hitIdx - 1 + hits.length) % hits.length); };
document.getElementById('close').onclick = () => {
  bar.style.display = 'none';
  hits = [];
  highlights.clear();
  if (pages) pages.redecorate();
};

// Called from the app's search menu.
window.showSearch = () => {
  if (!searcher) { alert('이 파일은 찾기를 지원하지 않습니다'); return; }
  bar.style.display = 'flex';
  qEl.focus();
};

// ---- print (HWP → PDF) ----------------------------------------------------------
// The app prints this page to a PDF file (ViewerActivity.sharePdf). Lazy pages
// are not all drawn, so lay out every page in a print-only container first.

let printSource = null; // { sizes, render(i) → svg } for HWP

window.preparePrint = async () => {
  try {
    if (!printSource) throw new Error('이 문서는 PDF로 만들 수 없습니다');
    const { sizes, render } = printSource;
    const w = sizes[0].w, h = sizes[0].h;
    let box = document.getElementById('print');
    if (box) box.remove();
    box = document.createElement('div');
    box.id = 'print';
    const style = document.createElement('style');
    style.textContent = `@page { size: ${w}px ${h}px; margin: 0; }
      @media print {
        body > *:not(#print) { display: none !important; }
        #print { display: block !important; }
        html, body { background: #fff; }
      }
      #print { display: none; }
      #print img { display: block; width: ${w}px; height: ${h}px; object-fit: contain; break-after: page; }`;
    box.appendChild(style);
    document.body.appendChild(box);
    for (let i = 0; i < sizes.length; i++) {
      const svg = await render(i);
      const img = new Image();
      img.src = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }));
      await img.decode().catch(() => {});
      box.appendChild(img);
      if (bridge.onPrintProgress) bridge.onPrintProgress(i + 1, sizes.length);
    }
    bridge.onPrintReady(w, h);
  } catch (e) {
    bridge.onPrintFail((e && e.message) || String(e));
  }
};

// ---- PDF ---------------------------------------------------------------------

async function openPdf() {
  const pdfjs = await import('./pdfjs/pdf.min.mjs');
  pdfjs.GlobalWorkerOptions.workerSrc = './pdfjs/pdf.worker.min.mjs';
  const data = await fetchBytes();
  const task = pdfjs.getDocument({
    data,
    cMapUrl: './pdfjs/cmaps/',
    cMapPacked: true,
    standardFontDataUrl: './pdfjs/standard_fonts/',
    wasmUrl: './pdfjs/wasm/',
    iccUrl: './pdfjs/iccs/',
  });
  task.onPassword = async (update, reason) => {
    const pw = await askPassword(reason === 2 ? '비밀번호가 틀렸습니다' : '비밀번호가 걸린 문서입니다');
    if (pw === null) { task.destroy(); fail('비밀번호 입력 취소'); return; }
    update(pw);
  };
  const doc = await task.promise;
  const sizes = [];
  for (let i = 1; i <= doc.numPages; i++) {
    const p = await doc.getPage(i);
    const v = p.getViewport({ scale: 1 });
    sizes.push({ w: v.width, h: v.height });
  }
  msgEl.style.display = 'none';
  const tasks = new Map();
  setupPages(sizes, async (i, el) => {
    const page = await doc.getPage(i + 1);
    const cssW = el.clientWidth;
    const base = page.getViewport({ scale: 1 });
    // Render sharper than the screen so pinch-zoom stays readable, but cap the
    // canvas size to keep WebView memory in check.
    let scale = (cssW / base.width) * (window.devicePixelRatio || 1) * 1.5;
    const maxPx = 12e6;
    if (base.width * base.height * scale * scale > maxPx) {
      scale = Math.sqrt(maxPx / (base.width * base.height));
    }
    const vp = page.getViewport({ scale });
    const c = document.createElement('canvas');
    c.width = Math.floor(vp.width);
    c.height = Math.floor(vp.height);
    const t = page.render({ canvasContext: c.getContext('2d'), viewport: vp });
    tasks.set(i, t);
    await t.promise;
    tasks.delete(i);
    el.innerHTML = '';
    el.appendChild(c);
  }, (i, el) => {
    const t = tasks.get(i);
    if (t) t.cancel();
    const c = el.querySelector('canvas');
    if (c) { c.width = 0; c.height = 0; }
  });

  // Text per page, joined across items so a word split over runs still matches.
  const textCache = new Map();
  async function pageText(i) {
    if (textCache.has(i)) return textCache.get(i);
    const page = await doc.getPage(i + 1);
    const vp = page.getViewport({ scale: 1 });
    const tc = await page.getTextContent();
    let str = '';
    const spans = []; // {start, end, item}
    for (const it of tc.items) {
      if (!it.str) continue;
      spans.push({ start: str.length, end: str.length + it.str.length, it });
      str += it.str;
      if (it.hasEOL) str += ' ';
    }
    const v = { str: str.toLowerCase(), spans, vp };
    textCache.set(i, v);
    return v;
  }
  searcher = async (q) => {
    const needle = q.toLowerCase();
    const out = [];
    for (let i = 0; i < doc.numPages && out.length < 1000; i++) {
      const { str, spans, vp } = await pageText(i);
      let at = str.indexOf(needle);
      while (at >= 0) {
        const end = at + needle.length;
        const rects = [];
        for (const s of spans) {
          if (s.end <= at || s.start >= end) continue;
          const it = s.it;
          const a = Math.max(at, s.start) - s.start;
          const b = Math.min(end, s.end) - s.start;
          const [, , , , x0, y0] = it.transform;
          const fontH = it.height || Math.hypot(it.transform[2], it.transform[3]);
          const x1 = x0 + it.width * (a / it.str.length);
          const x2 = x0 + it.width * (b / it.str.length);
          const [l, t, r, bt] = vp.convertToViewportRectangle([x1, y0 - fontH * 0.2, x2, y0 + fontH * 0.9]);
          rects.push({ x: Math.min(l, r), y: Math.min(t, bt), w: Math.abs(r - l), h: Math.abs(bt - t) });
        }
        if (rects.length) out.push({ page: i, rects });
        at = str.indexOf(needle, end);
      }
    }
    return out;
  };
  bridge.onReady(doc.numPages);
}

// ---- HWP / HWPX --------------------------------------------------------------

async function openHwp() {
  const worker = new Worker('./hwp-worker.js', { type: 'module' });
  const waiting = new Map(); // page index → {resolve, reject}
  const searches = new Map();
  let opened = null;

  worker.onmessage = (ev) => {
    const m = ev.data;
    if (m.type === 'page' || m.type === 'pageError') {
      const w = waiting.get(m.i);
      waiting.delete(m.i);
      if (w) m.type === 'page' ? w.resolve(m.svg) : w.reject(new Error(m.message));
    } else if (m.type === 'found') {
      const r = searches.get(m.id);
      searches.delete(m.id);
      if (r) r(m.hits);
    } else if (opened) {
      opened(m);
    }
  };
  const open = (password) => new Promise((resolve) => {
    opened = resolve;
    worker.postMessage({ type: 'open', src, password });
  });

  // Big documents take a while to lay out; say so instead of looking stuck.
  const slow = setTimeout(() => { msgEl.textContent = '큰 문서라 쪽을 나누는 중입니다…'; }, 1500);
  let res = await open(null);
  while (res.type === 'error' && res.needPassword) {
    const pw = await askPassword(/일치|틀/.test(res.message) ? '비밀번호가 틀렸습니다' : '비밀번호가 걸린 문서입니다');
    if (pw === null) throw new Error('비밀번호 입력 취소');
    res = await open(pw);
  }
  clearTimeout(slow);
  if (res.type === 'error') throw new Error(res.message);
  const sizes = res.sizes;
  const n = sizes.length;
  if (!n) throw new Error('쪽이 없습니다');

  const render = (i) => new Promise((resolve, reject) => {
    waiting.set(i, { resolve, reject });
    worker.postMessage({ type: 'render', i });
  });
  let searchId = 0;
  searcher = (q) => new Promise((resolve) => {
    const id = ++searchId;
    searches.set(id, resolve);
    worker.postMessage({ type: 'search', id, q });
  });

  printSource = { sizes, render };

  msgEl.style.display = 'none';
  const urls = new Map();
  setupPages(sizes, async (i, el) => {
    // Each page as its own SVG image: page SVGs reuse element ids
    // (clip paths etc.), so inlining several would make them collide.
    const svg = await render(i);
    const url = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }));
    urls.set(i, url);
    const img = new Image();
    img.decoding = 'async';
    img.src = url;
    await img.decode().catch(() => {});
    el.innerHTML = '';
    el.appendChild(img);
  }, (i) => {
    const u = urls.get(i);
    if (u) { URL.revokeObjectURL(u); urls.delete(i); }
  });
  bridge.onReady(n);
}

// ---- image / text ------------------------------------------------------------

async function openImage() {
  const img = document.createElement('img');
  img.id = 'img';
  img.src = src;
  await img.decode();
  msgEl.style.display = 'none';
  pagesEl.appendChild(img);
  bridge.onReady(1);
}

async function openText() {
  const data = await fetchBytes();
  let text;
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(data);
  } catch {
    text = new TextDecoder('euc-kr').decode(data); // old Korean text files
  }
  const pre = document.createElement('div');
  pre.id = 'text';
  pre.textContent = text.replace(/^﻿/, '');
  msgEl.style.display = 'none';
  pagesEl.appendChild(pre);
  bridge.onReady(1);
}

const openers = { pdf: openPdf, hwp: openHwp, image: openImage, text: openText };

(async () => {
  try {
    const f = openers[kind];
    if (!f) throw new Error('알 수 없는 형식 ' + kind);
    await f();
  } catch (e) {
    fail(e);
  }
})();
