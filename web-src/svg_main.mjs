import fs from 'fs';
import init, { HwpDocument } from '../research/rhwp/pkg-main/rhwp.js';
globalThis.measureTextWidth = (font, text) => { const m = /(\d+(?:\.\d+)?)px/.exec(font); return (m ? +m[1] : 12) * 0.55 * [...text].length; };
await init({ module_or_path: fs.readFileSync('../research/rhwp/pkg-main/rhwp_bg.wasm') });
const doc = new HwpDocument(new Uint8Array(fs.readFileSync(process.argv[2])));
fs.writeFileSync(process.argv[3], doc.renderPageSvg(Number(process.argv[4] || 0)));
