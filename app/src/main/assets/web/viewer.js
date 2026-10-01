// Document renderer inside the app's WebView.
// Query: kind = pdf | hwp | image | text, src = URL of the document bytes,
// optional meta = URL of a JSON sidecar (sheet names), pos = "page:frac" to
// resume at, night = 1 for night mode.
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

function notify(msg) {
  if (bridge.toast) bridge.toast(msg); else console.log(msg);
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

// ---- night mode ----------------------------------------------------------------

window.setNight = (on) => document.body.classList.toggle('night', !!on);
if (params.get('night') === '1') window.setNight(true);

// ---- lazy page machinery shared by pdf and hwp ------------------------------
// Each page gets a sized placeholder; content is drawn when it nears the
// viewport and dropped again when far away, so 500-page files stay light.

const KEEP = 4; // pages kept alive on each side of the visible one

// Search highlights per page, in page units: Map<page, [{x,y,w,h,current}]>.
const highlights = new Map();

let pages = null; // set by setupPages

// What an opener provides beyond drawing (all optional):
//   pageImage(i, cssWidth) → canvas      for thumbnails, slides, "page as image"
//   pageText(i) → string                  for read-aloud and the text index
//   outline() → [{title, page, depth}]    table of contents
const doc = { pageImage: null, pageText: null, outline: null, count: 0 };

// refWidth: an A4 page's width in the document's units. An A4 page fills the
// screen width; smaller pages (a two-row CSV sheet) keep that same scale instead
// of being blown up, and wider ones are fitted to the screen.
function setupPages(sizes, draw, release, refWidth) {
  function sizeSlot(el, s) {
    const screenW = pageWidthCss();
    const w = Math.min(screenW, Math.round(s.w * screenW / refWidth));
    el.style.width = w + 'px';
    el.style.height = Math.round(w * s.h / s.w) + 'px';
  }
  // gen counts how often a slot was reset (scrolled far away, rotated). A draw
  // that finishes for an older gen is thrown away instead of shown or kept.
  const slots = sizes.map((s, i) => {
    const d = document.createElement('div');
    d.className = 'page';
    sizeSlot(d, s);
    d.dataset.i = i;
    d.innerHTML = `<div class="ph">${i + 1}</div>`;
    pagesEl.appendChild(d);
    return { el: d, drawn: false, busy: false, gen: 0 };
  });

  let current = 0;
  const visible = new Set();
  const far = (i) => Math.abs(i - current) > KEEP * 2;

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

  function reset(i) {
    const s = slots[i];
    s.gen++;
    release(i, s.el); // also cancels a draw still in progress
    s.el.innerHTML = `<div class="ph">${i + 1}</div>`;
    s.drawn = false;
  }

  async function ensure(i) {
    const s = slots[i];
    if (!s || s.drawn || s.busy || far(i)) return;
    s.busy = true;
    const gen = s.gen;
    try {
      await draw(i, s.el);
      if (gen !== s.gen || far(i)) {
        // Outdated by now (scrolled away or resized while drawing).
        s.busy = false;
        reset(i);
        ensure(i);
        return;
      }
      s.drawn = true;
      decorate(i);
    } catch (e) {
      if (gen === s.gen) {
        s.el.innerHTML = `<div class="ph">${i + 1}쪽 표시 오류</div>`;
        console.error(e);
      }
    } finally {
      s.busy = false;
    }
  }

  function trim() {
    slots.forEach((s, i) => {
      if ((s.drawn || s.busy) && far(i)) reset(i);
    });
  }

  const io = new IntersectionObserver((entries) => {
    for (const en of entries) {
      const i = Number(en.target.dataset.i);
      if (en.isIntersecting) visible.add(i); else visible.delete(i);
    }
    if (visible.size) {
      // `visible` includes a screen of margin above and below (for preloading).
      current = Math.min(...visible);
      trim();
      for (let i = current - 1; i <= current + KEEP * 2; i++) ensure(i);
    }
  }, { rootMargin: '100% 0px' });
  slots.forEach((s) => io.observe(s.el));

  // The page under the middle of the screen, and how far down it we are (0..1).
  function readingPosition() {
    const mid = window.innerHeight / 2;
    let lo = 0, hi = slots.length - 1;
    while (lo < hi) {
      const m = (lo + hi) >> 1;
      if (slots[m].el.getBoundingClientRect().bottom < mid) lo = m + 1; else hi = m;
    }
    const r = slots[lo].el.getBoundingClientRect();
    return { i: lo, frac: Math.min(1, Math.max(0, (mid - r.top) / Math.max(1, r.height))) };
  }
  function scrollToPosition(pos) {
    const el = slots[pos.i].el;
    const top = el.getBoundingClientRect().top + window.scrollY + pos.frac * el.offsetHeight - window.innerHeight / 2;
    window.scrollTo({ top: Math.max(0, top) });
  }
  function updateCounter() {
    pageNumEl.textContent = `${readingPosition().i + 1} / ${slots.length}`;
  }
  let counterQueued = false;
  window.addEventListener('scroll', () => {
    if (counterQueued) return;
    counterQueued = true;
    requestAnimationFrame(() => { counterQueued = false; updateCounter(); });
  }, { passive: true });

  // Rotation: re-fit pages to the new width and redraw them at the new size,
  // keeping the reading position within the page.
  let lastW = document.documentElement.clientWidth;
  window.addEventListener('resize', () => {
    const nw = document.documentElement.clientWidth;
    if (nw === lastW) return;
    lastW = nw;
    const pos = readingPosition();
    slots.forEach((s, i) => {
      sizeSlot(s.el, sizes[i]);
      if (s.drawn || s.busy) reset(i);
    });
    scrollToPosition(pos);
    current = pos.i;
    for (let i = pos.i - 1; i <= pos.i + KEEP; i++) ensure(i);
  });

  pageNumEl.style.display = 'block';
  pageNumEl.textContent = `1 / ${slots.length}`;
  doc.count = slots.length;

  pages = {
    count: slots.length,
    // Scroll so page i (and optionally y in page units) sits near the top.
    scrollTo(i, y) {
      i = Math.max(0, Math.min(i, slots.length - 1));
      const s = slots[i];
      const k = s.el.clientWidth / sizes[i].w;
      const top = s.el.getBoundingClientRect().top + window.scrollY + (y ? y * k - 120 : -8);
      window.scrollTo({ top: Math.max(0, top) });
    },
    redecorate() {
      slots.forEach((s, i) => { if (s.drawn) decorate(i); });
    },
    position: readingPosition,
    restore(pos) {
      if (!pos || pos.i >= slots.length) return;
      current = pos.i;
      scrollToPosition(pos);
    },
  };

  // Resume where the reader left off last time.
  const saved = (params.get('pos') || '').split(':');
  if (saved.length === 2) pages.restore({ i: parseInt(saved[0], 10) || 0, frac: parseFloat(saved[1]) || 0 });
  ensure(current);
}

// Report the reading position when scrolling settles, for "resume where I was".
let posTimer = null;
window.addEventListener('scroll', () => {
  clearTimeout(posTimer);
  posTimer = setTimeout(() => {
    if (!bridge.savePos) return;
    if (pages) {
      const p = pages.position();
      bridge.savePos(`${p.i}:${p.frac.toFixed(3)}`);
    } else {
      const h = document.documentElement.scrollHeight - window.innerHeight;
      bridge.savePos(`y:${h > 0 ? (window.scrollY / h).toFixed(4) : 0}`);
    }
  }, 600);
}, { passive: true });

window.currentPage = () => (pages ? pages.position().i : 0);

// Page jump: the number is asked in the app's own dialog (prompt() would show the page URL).
window.gotoPage = (n) => { if (pages && n >= 1 && n <= pages.count) pages.scrollTo(n - 1); };
window.askJump = () => {
  if (!pages) { notify('쪽이 없는 문서입니다'); return; }
  if (bridge.askPage) { bridge.askPage(pages.count); return; }
  window.gotoPage(parseInt(prompt(`이동할 쪽 (1~${pages.count})`), 10));
};
pageNumEl.addEventListener('click', () => window.askJump());

// ---- double tap: zoom in / back out (done by the app's WebView) --------------------

let lastTap = { t: 0, x: 0, y: 0 };
pagesEl.addEventListener('touchend', (e) => {
  if (e.touches.length || e.changedTouches.length !== 1) return;
  const t = e.changedTouches[0];
  const now = e.timeStamp;
  if (now - lastTap.t < 300 && Math.hypot(t.clientX - lastTap.x, t.clientY - lastTap.y) < 30) {
    lastTap.t = 0;
    if (bridge.doubleTap) bridge.doubleTap(t.clientX, t.clientY);
  } else {
    lastTap = { t: now, x: t.clientX, y: t.clientY };
  }
}, { passive: true });

// ---- search ------------------------------------------------------------------
// Each opener sets `searcher` to async (query) → hits. Page hits are
// { page, rects: [{x,y,w,h}] }; text-view hits are { el } (a <mark>).

let searcher = null;
let hits = [];
let hitIdx = -1;

const bar = document.getElementById('search');
const qEl = document.getElementById('q');
const countEl = document.getElementById('count');

function showHit(idx) {
  hitIdx = idx;
  countEl.textContent = hits.length ? `${idx + 1} / ${hits.length}` : '없음';
  if (hits.length && hits[0].el) {
    hits.forEach((h, n) => h.el.classList.toggle('cur', n === idx));
    if (hits[idx]) hits[idx].el.scrollIntoView({ block: 'center' });
    return;
  }
  highlights.clear();
  hits.forEach((h, n) => {
    const list = highlights.get(h.page) || [];
    h.rects.forEach((r) => list.push({ ...r, current: n === idx }));
    highlights.set(h.page, list);
  });
  pages.redecorate();
  if (hits[idx]) pages.scrollTo(hits[idx].page, hits[idx].rects[0].y);
}

let searchSeq = 0;
let lastQuery = '';
let clearSearch = () => {};

async function runSearch() {
  const q = qEl.value.trim();
  if (!q || !searcher) return;
  const my = ++searchSeq; // a newer search, or closing the bar, makes this one stale
  lastQuery = q;
  countEl.textContent = '찾는 중…';
  let found;
  try {
    found = await searcher(q);
  } catch (e) {
    found = [];
    console.error(e);
  }
  if (my !== searchSeq || bar.style.display === 'none') return;
  hits = found;
  showHit(hits.length ? 0 : -1);
}

qEl.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { qEl.blur(); runSearch(); }
});
function step(d) {
  if (qEl.value.trim() !== lastQuery) { runSearch(); return; }
  if (hits.length) showHit((hitIdx + d + hits.length) % hits.length);
}
document.getElementById('next').onclick = () => step(1);
document.getElementById('prev').onclick = () => step(-1);
document.getElementById('close').onclick = () => {
  searchSeq++;
  bar.style.display = 'none';
  hits = [];
  highlights.clear();
  clearSearch();
  if (pages) pages.redecorate();
};

// Called from the app's search menu.
window.showSearch = () => {
  if (!searcher) {
    notify(kind === 'image' ? '그림에서는 찾기를 할 수 없습니다' : '문서를 여는 중입니다');
    return;
  }
  bar.style.display = 'flex';
  qEl.focus();
};

// ---- overlays: table of contents, page thumbnails, slideshow ------------------

const overlay = document.getElementById('overlay');
function openOverlay(title, body) {
  overlay.innerHTML = '';
  const head = document.createElement('div');
  head.className = 'ov-head';
  head.innerHTML = `<span>${title}</span><button aria-label="닫기">✕</button>`;
  head.querySelector('button').onclick = closeOverlay;
  overlay.appendChild(head);
  overlay.appendChild(body);
  overlay.style.display = 'flex';
  document.body.classList.add('ov-open');
  if (bridge.onOverlay) bridge.onOverlay(true);
}
function closeOverlay() {
  overlay.style.display = 'none';
  overlay.innerHTML = '';
  document.body.classList.remove('ov-open');
  if (bridge.onOverlay) bridge.onOverlay(false);
}
// Back button: leave the slideshow (keeping its page) or close the open overlay.
window.closeAny = () => {
  if (window.__slidesEnd) window.endSlides();
  else if (overlay.style.display === 'flex') closeOverlay();
};

window.showOutline = async () => {
  if (!pages) { notify('쪽이 없는 문서입니다'); return; }
  const items = doc.outline ? await doc.outline() : [];
  if (!items.length) { notify('이 문서에는 목차가 없습니다'); return; }
  const list = document.createElement('div');
  list.className = 'ov-list';
  for (const it of items) {
    const row = document.createElement('button');
    row.className = 'ov-row';
    row.style.paddingLeft = `${14 + it.depth * 16}px`;
    row.innerHTML = `<span></span><em>${it.page + 1}</em>`;
    row.firstChild.textContent = it.title;
    row.onclick = () => { closeOverlay(); pages.scrollTo(it.page); };
    list.appendChild(row);
  }
  openOverlay('목차', list);
};

window.showThumbs = () => {
  if (!pages || !doc.pageImage) { notify('쪽이 없는 문서입니다'); return; }
  const grid = document.createElement('div');
  grid.className = 'ov-grid';
  const cur = pages.position().i;
  const tileW = Math.floor((document.documentElement.clientWidth - 40) / 3);
  const io = new IntersectionObserver((entries) => {
    for (const en of entries) {
      if (!en.isIntersecting || en.target.dataset.done) continue;
      en.target.dataset.done = '1';
      const i = Number(en.target.dataset.i);
      doc.pageImage(i, tileW).then((c) => {
        const box = en.target.querySelector('.tb');
        box.innerHTML = '';
        box.appendChild(c);
      }).catch(() => {});
    }
  }, { root: overlay, rootMargin: '200px 0px' });
  for (let i = 0; i < pages.count; i++) {
    const t = document.createElement('button');
    t.className = i === cur ? 'tile cur' : 'tile';
    t.dataset.i = i;
    t.innerHTML = `<div class="tb"></div><span>${i + 1}</span>`;
    t.onclick = () => { closeOverlay(); pages.scrollTo(i); };
    grid.appendChild(t);
    io.observe(t);
  }
  openOverlay(`쪽 보기 (${pages.count}쪽)`, grid);
  setTimeout(() => grid.children[cur] && grid.children[cur].scrollIntoView({ block: 'center' }), 50);
};

// One page at a time, full screen; swipe or tap the sides to move.
window.startSlides = () => {
  if (!pages || !doc.pageImage) { notify('쪽이 없는 문서입니다'); return; }
  let at = pages.position().i;
  const stage = document.createElement('div');
  stage.className = 'slides';
  const counter = document.createElement('div');
  counter.className = 'slide-n';
  stage.appendChild(counter);
  let drawn = 0;
  async function show(i) {
    at = Math.max(0, Math.min(pages.count - 1, i));
    counter.textContent = `${at + 1} / ${pages.count}`;
    const my = ++drawn;
    const W = window.innerWidth, H = window.innerHeight;
    const c = await doc.pageImage(at, W * 2);
    if (my !== drawn) return;
    const k = Math.min(W / c.width, H / c.height);
    c.style.width = `${c.width * k}px`;
    c.style.height = `${c.height * k}px`;
    stage.querySelectorAll('canvas').forEach((x) => x.remove());
    stage.insertBefore(c, counter);
  }
  let sx = 0, sy = 0;
  stage.addEventListener('touchstart', (e) => { sx = e.touches[0].clientX; sy = e.touches[0].clientY; }, { passive: true });
  stage.addEventListener('touchend', (e) => {
    const t = e.changedTouches[0];
    const dx = t.clientX - sx, dy = t.clientY - sy;
    if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) show(at + (dx < 0 ? 1 : -1));
    else if (Math.abs(dx) < 10 && Math.abs(dy) < 10) {
      if (t.clientX > window.innerWidth * 0.66) show(at + 1);
      else if (t.clientX < window.innerWidth * 0.33) show(at - 1);
    }
  }, { passive: true });
  overlay.innerHTML = '';
  overlay.appendChild(stage);
  overlay.style.display = 'flex';
  document.body.classList.add('ov-open');
  if (bridge.onOverlay) bridge.onOverlay(true);
  window.__slidesEnd = () => { pages.scrollTo(at); };
  show(at);
};
window.endSlides = () => {
  if (window.__slidesEnd) window.__slidesEnd();
  window.__slidesEnd = null;
  closeOverlay();
};

// ---- the current page as a picture (to send over a messenger) -------------------

window.sharePageImage = async () => {
  if (!pages || !doc.pageImage) { notify('쪽이 없는 문서입니다'); return; }
  const i = pages.position().i;
  try {
    const c = await doc.pageImage(i, 1600);
    const ctx = c.getContext('2d');
    // Pages are transparent where empty; put white behind them.
    ctx.globalCompositeOperation = 'destination-over';
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, c.width, c.height);
    bridge.savePng(i + 1, c.toDataURL('image/png').split(',')[1]);
  } catch (e) {
    notify('쪽 그림을 만들지 못했습니다');
  }
};

// ---- page text for read-aloud and the search index -----------------------------

window.speakPage = async (i) => {
  if (!doc.pageText || i >= doc.count) { bridge.onPageText(-1, ''); return; }
  let t = '';
  try { t = await doc.pageText(i); } catch { t = ''; }
  bridge.onPageText(i, t || '');
};

// After opening, collect all text (quietly, a page at a time) so the app can
// find this document by its contents later.
async function indexText() {
  if (!bridge.saveText || !doc.pageText) return;
  await new Promise((r) => setTimeout(r, 3000));
  let all = '';
  for (let i = 0; i < doc.count && all.length < 2_000_000; i++) {
    try { all += (await doc.pageText(i)) + '\n'; } catch { /* skip page */ }
    if (i % 5 === 4) await new Promise((r) => setTimeout(r, 30));
  }
  bridge.saveText(all);
}

// ---- sheet tabs (spreadsheets converted one sheet per page) ----------------------

async function showSheetTabs() {
  const metaUrl = params.get('meta');
  if (!metaUrl) return;
  try {
    const meta = await (await fetch(metaUrl)).json();
    const sheets = meta.sheets || [];
    if (sheets.length < 2) return;
    const tabs = document.getElementById('tabs');
    sheets.forEach((s) => {
      const b = document.createElement('button');
      b.textContent = s.name;
      b.onclick = () => {
        tabs.querySelectorAll('button').forEach((x) => x.classList.remove('on'));
        b.classList.add('on');
        pages.scrollTo(s.page);
      };
      tabs.appendChild(b);
    });
    tabs.firstChild.classList.add('on');
    tabs.style.display = 'flex';
    document.body.classList.add('has-tabs');
  } catch { /* no tabs */ }
}

// ---- print (HWP → PDF / printer) -------------------------------------------------
// The app prints this page (ViewerActivity). Lazy pages are not all drawn, so
// lay out every page in a print-only container first. Pages are inline SVG so
// their text stays text in the PDF (searchable, copyable).

let printSource = null; // { sizes, svg(i) → prefixed inline svg string } for HWP

window.preparePrint = async (mode) => {
  try {
    if (!printSource) throw new Error('이 문서는 PDF로 만들 수 없습니다');
    const { sizes, svg } = printSource;
    const w = sizes[0].w, h = sizes[0].h;
    window.cleanupPrint();
    const box = document.createElement('div');
    box.id = 'print';
    // Each distinct page size gets a named @page, so landscape pages print on
    // landscape paper instead of being shrunk onto the first page's size.
    const names = new Map();
    let pageRules = '';
    for (const s of sizes) {
      const key = `${Math.round(s.w)}x${Math.round(s.h)}`;
      if (!names.has(key)) {
        names.set(key, `p${names.size}`);
        pageRules += `@page p${names.size - 1} { size: ${s.w}px ${s.h}px; margin: 0; }\n`;
      }
    }
    const style = document.createElement('style');
    style.textContent = `@page { size: ${w}px ${h}px; margin: 0; }
      ${pageRules}
      @media print {
        body > *:not(#print) { display: none !important; }
        #print { display: block !important; }
        html, body { background: #fff; }
        body.night #print { filter: none; }
      }
      #print { display: none; }
      #print .pp { display: block; overflow: hidden; break-after: page; }
      #print .pp > svg { display: block; width: 100%; height: 100%; }`;
    box.appendChild(style);
    document.body.appendChild(box);
    for (let i = 0; i < sizes.length; i++) {
      const s = sizes[i];
      const d = document.createElement('div');
      d.className = 'pp';
      d.style.width = `${s.w}px`;
      d.style.height = `${s.h}px`;
      d.style.page = names.get(`${Math.round(s.w)}x${Math.round(s.h)}`);
      d.innerHTML = await svg(i, `q${i}`);
      box.appendChild(d);
      if (bridge.onPrintProgress) bridge.onPrintProgress(i + 1, sizes.length);
    }
    bridge.onPrintReady(w, h, mode || 'pdf');
  } catch (e) {
    window.cleanupPrint();
    bridge.onPrintFail((e && e.message) || String(e));
  }
};

// Drops the print-only pages once the PDF is written.
window.cleanupPrint = () => {
  const box = document.getElementById('print');
  if (box) box.remove();
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
  const pdf = await task.promise;
  const sizes = [];
  for (let i = 1; i <= pdf.numPages; i++) {
    const p = await pdf.getPage(i);
    const v = p.getViewport({ scale: 1 });
    sizes.push({ w: v.width, h: v.height });
  }
  msgEl.style.display = 'none';

  // Destination (named or explicit) → page index.
  async function destPage(dest) {
    const d = typeof dest === 'string' ? await pdf.getDestination(dest) : dest;
    if (!Array.isArray(d) || !d.length) return null;
    return typeof d[0] === 'number' ? d[0] : pdf.getPageIndex(d[0]);
  }

  const tasks = new Map();
  setupPages(sizes, async (i, el) => {
    const page = await pdf.getPage(i + 1);
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

    // Selectable text over the picture, and tappable links.
    const cssVp = page.getViewport({ scale: cssW / base.width });
    el.style.setProperty('--total-scale-factor', cssVp.scale);
    el.style.setProperty('--scale-round-x', '1px');
    el.style.setProperty('--scale-round-y', '1px');
    const tl = document.createElement('div');
    tl.className = 'textLayer';
    el.appendChild(tl);
    try {
      await new pdfjs.TextLayer({ textContentSource: page.streamTextContent(), container: tl, viewport: cssVp }).render();
    } catch { /* picture only */ }
    try {
      for (const a of await page.getAnnotations({ intent: 'display' })) {
        if (a.subtype !== 'Link' || (!a.url && !a.dest)) continue;
        const [x1, y1] = cssVp.convertToViewportPoint(a.rect[0], a.rect[1]);
        const [x2, y2] = cssVp.convertToViewportPoint(a.rect[2], a.rect[3]);
        const link = document.createElement('a');
        link.className = 'lnk';
        link.style.left = `${Math.min(x1, x2)}px`;
        link.style.top = `${Math.min(y1, y2)}px`;
        link.style.width = `${Math.abs(x2 - x1)}px`;
        link.style.height = `${Math.abs(y2 - y1)}px`;
        if (a.url) {
          link.href = a.url;
        } else {
          link.href = '#';
          link.onclick = async (ev) => {
            ev.preventDefault();
            const p = await destPage(a.dest);
            if (p !== null) pages.scrollTo(p);
          };
        }
        el.appendChild(link);
      }
    } catch { /* no links */ }
  }, (i, el) => {
    const t = tasks.get(i);
    if (t) t.cancel();
    const c = el.querySelector('canvas');
    if (c) { c.width = 0; c.height = 0; }
  }, 595); // A4 width in PDF points

  doc.pageImage = async (i, cssWidth) => {
    const page = await pdf.getPage(i + 1);
    const base = page.getViewport({ scale: 1 });
    let scale = cssWidth / base.width;
    if (base.width * base.height * scale * scale > 16e6) scale = Math.sqrt(16e6 / (base.width * base.height));
    const vp = page.getViewport({ scale });
    const c = document.createElement('canvas');
    c.width = Math.floor(vp.width);
    c.height = Math.floor(vp.height);
    await page.render({ canvasContext: c.getContext('2d'), viewport: vp }).promise;
    return c;
  };

  doc.outline = async () => {
    const out = [];
    async function walk(items, depth) {
      for (const it of items || []) {
        const p = it.dest ? await destPage(it.dest).catch(() => null) : null;
        if (p !== null) out.push({ title: it.title, page: p, depth });
        await walk(it.items, depth + 1);
      }
    }
    await walk(await pdf.getOutline(), 0);
    if (out.length) return out;
    // No bookmarks (common for converted Word files): lines set clearly larger
    // than the body text are taken as headings.
    const lines = [];
    const chars = new Map();
    for (let i = 0; i < pdf.numPages && i < 600; i++) {
      const tc = await (await pdf.getPage(i + 1)).getTextContent();
      let cur = null;
      for (const it of tc.items) {
        if (!it.str) continue;
        const size = Math.round((it.height || Math.hypot(it.transform[2], it.transform[3])) * 2) / 2;
        const y = it.transform[5];
        if (cur && Math.abs(cur.y - y) < 1 && cur.size === size) cur.text += it.str;
        else { cur = { text: it.str, size, y, page: i }; lines.push(cur); }
        chars.set(size, (chars.get(size) || 0) + it.str.length);
      }
    }
    let body = 0, most = 0;
    for (const [s, n] of chars) if (n > most) { most = n; body = s; }
    const heads = lines.filter((l) => {
      const t = l.text.trim();
      return l.size >= body * 1.25 && t.length <= 60 && (t.match(/[가-힣A-Za-z]/g) || []).length >= 2;
    });
    const levels = [...new Set(heads.map((h) => h.size))].sort((a, b) => b - a);
    return heads.slice(0, 300).map((h) => ({ title: h.text.trim(), page: h.page, depth: Math.min(2, levels.indexOf(h.size)) }));
  };

  // Text per page, joined across items so a word split over runs still matches.
  const textCache = new Map();
  async function pageText(i) {
    if (textCache.has(i)) return textCache.get(i);
    const page = await pdf.getPage(i + 1);
    const vp = page.getViewport({ scale: 1 });
    const tc = await page.getTextContent();
    let str = '';
    const spans = []; // {start, end, item}
    for (const it of tc.items) {
      if (!it.str) continue;
      spans.push({ start: str.length, end: str.length + it.str.length, it });
      str += it.str;
      if (it.hasEOL) str += '\n';
    }
    const v = { raw: str, str: str.toLowerCase(), spans, vp };
    textCache.set(i, v);
    return v;
  }
  doc.pageText = async (i) => (await pageText(i)).raw;

  searcher = async (q) => {
    const needle = q.toLowerCase();
    const out = [];
    for (let i = 0; i < pdf.numPages && out.length < 1000; i++) {
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
          // (pdf.js 6 dropped convertToViewportRectangle; map the two corners.)
          const [l, t] = vp.convertToViewportPoint(x1, y0 - fontH * 0.2);
          const [r, bt] = vp.convertToViewportPoint(x2, y0 + fontH * 0.9);
          rects.push({ x: Math.min(l, r), y: Math.min(t, bt), w: Math.abs(r - l), h: Math.abs(bt - t) });
        }
        if (rects.length) out.push({ page: i, rects });
        at = str.indexOf(needle, end);
      }
    }
    return out;
  };
  showSheetTabs();
  bridge.onReady(pdf.numPages);
  indexText();
}

// ---- HWP / HWPX --------------------------------------------------------------

// Page SVGs reuse element ids (clip paths, gradients). To place several in one
// document — on screen and in the print container — give each page's ids a prefix.
function prefixIds(svg, p) {
  return svg
    .replace(/\bid="([^"]+)"/g, `id="${p}-$1"`)
    .replace(/url\(#([^)]+)\)/g, `url(#${p}-$1)`)
    .replace(/href="#([^"]+)"/g, `href="#${p}-$1"`);
}

async function openHwp() {
  const worker = new Worker('./hwp-worker.js', { type: 'module' });
  // Requests carry their own id: the screen and the PDF printer can ask for
  // the same page at the same time, and both must get an answer.
  const waiting = new Map(); // request id → {resolve, reject}
  let nextId = 0;
  let opened = null;

  worker.onmessage = (ev) => {
    const m = ev.data;
    if (m.id !== undefined && waiting.has(m.id)) {
      const w = waiting.get(m.id);
      waiting.delete(m.id);
      if (m.type === 'page') w.resolve(m.svg);
      else if (m.type === 'found') w.resolve(m.hits);
      else if (m.type === 'text') w.resolve(m.text);
      else if (m.type === 'outline') w.resolve(m.items);
      else w.reject(new Error(m.message));
    } else if (opened) {
      opened(m);
    }
  };
  worker.onerror = (e) => { if (opened) opened({ type: 'error', message: e.message || '한글 엔진 오류' }); };
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

  const ask = (msg) => new Promise((resolve, reject) => {
    const id = ++nextId;
    waiting.set(id, { resolve, reject });
    worker.postMessage({ ...msg, id });
  });
  const render = (i) => ask({ type: 'render', i });
  searcher = (q) => ask({ type: 'search', q });
  const inlineSvg = async (i, p) => prefixIds(await render(i), p);

  printSource = { sizes, svg: inlineSvg };

  doc.pageText = (i) => ask({ type: 'text', i });
  doc.outline = () => ask({ type: 'outline' });
  doc.pageImage = async (i, cssWidth) => {
    const svg = await render(i);
    const url = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }));
    try {
      const img = new Image();
      img.src = url;
      await img.decode();
      const s = sizes[i];
      const k = Math.min(cssWidth / s.w, Math.sqrt(16e6 / (s.w * s.h)));
      const c = document.createElement('canvas');
      c.width = Math.round(s.w * k);
      c.height = Math.round(s.h * k);
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
      return c;
    } finally {
      URL.revokeObjectURL(url);
    }
  };

  msgEl.style.display = 'none';
  setupPages(sizes, async (i, el) => {
    // Inline SVG (ids prefixed per page): the text in it can be selected and copied.
    const svg = await inlineSvg(i, `s${i}`);
    el.innerHTML = svg;
  }, () => {}, 794); // A4 width in rhwp px (96dpi)
  bridge.onReady(n);
  indexText();
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
  // A byte-order mark names the encoding (Excel's "Unicode text" is UTF-16LE).
  if (data[0] === 0xFF && data[1] === 0xFE) text = new TextDecoder('utf-16le').decode(data);
  else if (data[0] === 0xFE && data[1] === 0xFF) text = new TextDecoder('utf-16be').decode(data);
  else {
    try {
      text = new TextDecoder('utf-8', { fatal: true }).decode(data);
    } catch {
      text = new TextDecoder('euc-kr').decode(data); // old Korean text files
    }
  }
  text = text.replace(/^﻿/, '');
  msgEl.style.display = 'none';
  const ext = src.split('.').pop().toLowerCase();
  let root;
  if (ext === 'csv' || ext === 'tsv') {
    root = csvTable(text, ext === 'tsv' ? '\t' : ',');
  } else {
    root = document.createElement('div');
    root.id = 'text';
    root.textContent = text;
  }
  pagesEl.appendChild(root);
  textSearch(root);
  doc.count = 1;
  doc.pageText = async () => text;
  // Resume position (fraction of the whole text).
  const pos = params.get('pos') || '';
  if (pos.startsWith('y:')) {
    const f = parseFloat(pos.slice(2)) || 0;
    requestAnimationFrame(() => window.scrollTo({ top: f * (document.documentElement.scrollHeight - window.innerHeight) }));
  }
  bridge.onReady(1);
  indexText();
}

// Find in a text or table view: wrap matches in <mark>.
function textSearch(root) {
  clearSearch = () => {
    root.querySelectorAll('mark').forEach((m) => m.replaceWith(document.createTextNode(m.textContent)));
    root.normalize();
  };
  searcher = async (q) => {
    clearSearch();
    const needle = q.toLowerCase();
    const found = [];
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    for (const node of nodes) {
      const lower = node.data.toLowerCase();
      let at = lower.indexOf(needle);
      let cur = node;
      let offset = 0;
      while (at >= 0 && found.length < 5000) {
        const startInCur = at - offset;
        const hit = cur.splitText(startInCur);
        const rest = hit.splitText(needle.length);
        const mark = document.createElement('mark');
        hit.replaceWith(mark);
        mark.appendChild(hit);
        found.push({ el: mark });
        cur = rest;
        offset = at + needle.length;
        at = lower.indexOf(needle, offset);
      }
    }
    return found;
  };
}

// CSV/TSV as a table (quoted fields, "" escapes, line breaks inside quotes).
function csvTable(text, sep) {
  const rows = [];
  let row = [], field = '', quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') { field += '"'; i++; }
      else if (c === '"') quoted = false;
      else field += c;
    } else if (c === '"' && field === '') quoted = true;
    else if (c === sep) { row.push(field); field = ''; }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(field); rows.push(row); row = []; field = '';
    } else field += c;
  }
  if (field !== '' || row.length) { row.push(field); rows.push(row); }

  const wrap = document.createElement('div');
  wrap.id = 'sheet';
  const table = document.createElement('table');
  const MAX_ROWS = 20000;
  if (rows.length > MAX_ROWS) {
    const note = document.createElement('div');
    note.className = 'note';
    note.textContent = `전체 ${rows.length.toLocaleString()}행 중 앞 ${MAX_ROWS.toLocaleString()}행만 표시합니다`;
    wrap.appendChild(note);
  }
  rows.slice(0, MAX_ROWS).forEach((r, ri) => {
    const tr = table.insertRow();
    const th = document.createElement('th');
    th.textContent = ri + 1;
    tr.appendChild(th);
    r.forEach((v) => { tr.insertCell().textContent = v; });
  });
  wrap.appendChild(table);
  return wrap;
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
