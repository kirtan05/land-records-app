// Clone an already-fetched VF-7/12 folder to another survey that shares the same parent block
// (the old-scanned archive is identical across paiki of one block). No re-download / captcha.
//   node packages/anyror/clone-vf712.mjs <srcToken> <dstToken> <dstSurveyKey>
import { readFileSync, writeFileSync, existsSync, readdirSync, mkdirSync, rmSync, copyFileSync } from 'node:fs';
import { join, basename } from 'node:path';
import { execFileSync } from 'node:child_process';
import { REPO } from '../core/repo-root.mjs';

const OUT = REPO+'/output';
const STATE = join(OUT, '_vf712_state.json');
const [srcTok, dstTok, dstKey] = process.argv.slice(2);
if (!srcTok || !dstTok || !dstKey) { console.error('usage: clone-vf712.mjs <srcToken> <dstToken> <dstSurveyKey>'); process.exit(1); }

const srcDir = join(OUT, srcTok, 'oldvf712');
const dstDir = join(OUT, dstTok, 'oldvf712');
rmSync(dstDir, { recursive: true, force: true }); mkdirSync(dstDir, { recursive: true });

const periodOf = (f) => (basename(f).match(/_(\d{4}-\d{4})_/) || [, ''])[1];
const startYear = (f) => { const m = periodOf(f).match(/(\d{4})/); return m ? +m[1] : 9999; };

const srcFiles = readdirSync(srcDir).filter((f) => f.endsWith('.pdf') && !/_ALL\.pdf$/i.test(f));
const kept = [];
for (const f of srcFiles) { const dst = join(dstDir, f.replaceAll(srcTok, dstTok)); copyFileSync(join(srcDir, f), dst); kept.push(dst); }
kept.sort((a, b) => startYear(a) - startYear(b));

const combined = join(dstDir, `VF712_${dstTok}_ALL.pdf`);
if (kept.length === 1) writeFileSync(combined, readFileSync(kept[0]));
else execFileSync('pdfunite', [...kept, combined]);

// clone manifest
const srcMan = join(OUT, srcTok, `vf712_${srcTok}.json`);
if (existsSync(srcMan)) {
  const man = JSON.parse(readFileSync(srcMan, 'utf8').replaceAll(srcTok, dstTok));
  man.survey = dstKey; man.cloned_from = srcTok; man.combined = combined;
  writeFileSync(join(OUT, dstTok, `vf712_${dstTok}.json`), JSON.stringify(man, null, 2));
}

const state = existsSync(STATE) ? JSON.parse(readFileSync(STATE, 'utf8')) : {};
const srcState = state[Object.keys(state).find((k) => state[k].token === srcTok)] || {};
state[dstKey] = { done: true, token: dstTok, dir: dstDir, combined, kept: kept.length, total: srcState.total || kept.length, periods: kept.map(periodOf), cloned_from: srcTok, at: new Date().toISOString() };
writeFileSync(STATE, JSON.stringify(state, null, 2));
console.log(`cloned ${srcTok} -> ${dstTok} (${dstKey}): ${kept.length} docs, combined VF712_${dstTok}_ALL.pdf`);
