import fs from 'fs';
import init, { HwpDocument } from '../research/rhwp/pkg-main/rhwp.js';
let calls = 0, t = 0;
globalThis.measureTextWidth = (font, text) => { calls++; const a = performance.now(); const m = /(\d+(?:\.\d+)?)px/.exec(font); const r = (m ? +m[1] : 12) * 0.55 * [...text].length; t += performance.now() - a; return r; };
await init({ module_or_path: fs.readFileSync('../research/rhwp/pkg-main/rhwp_bg.wasm') });
const t0 = Date.now();
const doc = new HwpDocument(new Uint8Array(fs.readFileSync(process.argv[2])));
console.log('load', Date.now() - t0, 'ms; measure calls', calls, 'js time', Math.round(t));
