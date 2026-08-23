// Sends the Bharoda records to a WhatsApp group via Baileys (uses saved creds from login).
//   node packages/whatsapp/send.mjs --dry-run                 # show plan, don't connect/send
//   node packages/whatsapp/send.mjs --group="Family"          # match group by name
//   node packages/whatsapp/send.mjs 12036...@g.us             # exact group JID
import makeWASocket, { useMultiFileAuthState, fetchLatestBaileysVersion, DisconnectReason } from '@whiskeysockets/baileys';
import pino from 'pino';
import { readFileSync, existsSync, statSync } from 'node:fs';
import { join, basename } from 'node:path';
import { surveyToken } from '../core/normalize.mjs';
import { REPO } from '../core/repo-root.mjs';

const ROOT = REPO;
const OUT = join(ROOT, 'output');
const ZIP = join(ROOT, 'Bharoda_iRCMS_Cases.zip');
const DRY = process.argv.includes('--dry-run');
const SKIP_ZIP = process.argv.includes('--skip-zip');
const SEND_DELAY = process.argv.includes('--slow') ? 5000 : (parseInt((process.argv.find((a) => a.startsWith('--delay=')) || '').split('=')[1]) || 2300);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const groupArg = (process.argv.find((a) => a.startsWith('--group=')) || '').split('=')[1] || process.argv.find((a) => a.endsWith('@g.us'));

// ---- build the action list (text + documents, in order) ----
const targets = JSON.parse(readFileSync(join(ROOT, 'data/catalog/survey-input.json'), 'utf8')).filter((r) => r.matched);
const astate = existsSync(join(OUT, '_anyror_state.json')) ? JSON.parse(readFileSync(join(OUT, '_anyror_state.json'), 'utf8')) : {};
const doc = (path, caption, mime = 'application/pdf') => ({ type: 'doc', path, caption, mime, fileName: basename(path) });
const text = (t) => ({ type: 'text', text: t });

const actions = [];
actions.push(text(`📑 *Bharoda Land Records*\nDistrict Anand · Taluka Umreth · Village Bharoda\n${targets.length} survey numbers — each survey's AnyRoR land record + iRCMS case & order PDFs follow.`));
let cases = 0, orders = 0, lands = 0;
for (const t of targets) {
  const key = t.normalized, token = surveyToken(key), a = astate[key];
  const sumPath = join(OUT, token, '_summary.json');
  const recs = existsSync(sumPath) ? JSON.parse(readFileSync(sumPath, 'utf8')).records : [];
  const disp = recs.filter((r) => /DISPOS/i.test(r.status || '')).length;
  actions.push(text(`━━━━━━━━━━━━━━\n📋 *Survey No ${key}* — Bharoda\n${recs.length} iRCMS case(s) · ${disp} disposed · ${recs.length - disp} pending` + (a?.area ? `\n🗺️ Land area ${a.area} · ${a.land_use || ''}` : '')));
  if (a?.pdf && existsSync(a.pdf)) { actions.push(doc(a.pdf, `🗺️ AnyRoR Integrated Land Record — ${key}`)); lands++; }
  recs.forEach((r) => {
    if (r.case_pdf && existsSync(r.case_pdf)) { actions.push(doc(r.case_pdf, `📄 ${key} — Case ${r.case_index}/${recs.length}: ${r.registration_no || ''} [${r.status || ''}]`)); cases++; }
    String(r.order_pdf || '').split(';').filter(Boolean).filter(existsSync).forEach((op, k, arr) => { actions.push(doc(op, `⚖️ Order — ${key} Case ${r.case_index}${arr.length > 1 ? ` (${k + 1})` : ''}`)); orders++; });
  });
}
actions.push(text(`✅ Done — ${targets.length} surveys · ${cases} case PDFs · ${orders} order PDFs · ${lands} AnyRoR land records.`));
let zipMB = 0;
if (existsSync(ZIP) && !SKIP_ZIP) {
  zipMB = statSync(ZIP).size / 1048576;
  if (zipMB <= 100) actions.push(doc(ZIP, `📦 Complete bundle (${zipMB.toFixed(0)} MB) — all PDFs + Excel`, 'application/zip'));
  else actions.push(text(`📦 The full bundle zip is ${zipMB.toFixed(0)} MB — over WhatsApp's ~100 MB limit, so it's not attached. It's on the PC; I can split it into parts if you want.`));
}

const docCount = actions.filter((a) => a.type === 'doc').length;
const mins = Math.round(actions.length * SEND_DELAY / 1000 / 60);
console.log(`PLAN: ${actions.length} messages | ${docCount} documents · ${actions.filter((a) => a.type === 'text').length} text | ~${mins} min at ${SEND_DELAY}ms spacing`);
console.log(`  iRCMS: ${cases} case + ${orders} order PDFs | AnyRoR: ${lands} land records | zip: ${zipMB ? zipMB.toFixed(0) + 'MB ' + (zipMB <= 100 ? '(attached)' : '(too big, noted)') : 'none'}`);
console.log('  sample captions:'); actions.filter((a) => a.type === 'doc').slice(0, 4).forEach((a) => console.log('    •', a.caption));
if (DRY) process.exit(0);

// ---- resolve group + connect + send ----
if (!groupArg) { console.error('No group specified. Use --group="name" or a JID.'); process.exit(1); }
const groups = existsSync(join(ROOT, 'packages/whatsapp/groups.json')) ? JSON.parse(readFileSync(join(ROOT, 'packages/whatsapp/groups.json'), 'utf8')) : [];
let jid = groupArg.endsWith('@g.us') ? groupArg : null;
if (!jid) {
  const m = groups.filter((g) => g.name.toLowerCase().includes(groupArg.toLowerCase()));
  if (m.length === 1) jid = m[0].id;
  else { console.error(m.length ? 'Ambiguous group name, matches: ' + m.map((g) => g.name).join(' | ') : 'No group matches: ' + groupArg); process.exit(1); }
}
console.log('target group jid:', jid);

const { state, saveCreds } = await useMultiFileAuthState(join(ROOT, 'packages/whatsapp/auth'));
const { version } = await fetchLatestBaileysVersion();
const sock = makeWASocket({ version, auth: state, logger: pino({ level: 'silent' }), browser: ['Bharoda Records', 'Chrome', '1.0'] });
sock.ev.on('creds.update', saveCreds);
await new Promise((resolve, reject) => {
  sock.ev.on('connection.update', (u) => {
    if (u.qr) reject(new Error('Not logged in — run `node packages/whatsapp/login.mjs` and scan first.'));
    if (u.connection === 'open') resolve();
    if (u.connection === 'close') { const c = u.lastDisconnect?.error?.output?.statusCode; if (c === DisconnectReason.loggedOut) reject(new Error('Logged out — re-run login.')); }
  });
});
console.log('connected; sending in 3s…'); await sleep(3000);

for (const [i, act] of actions.entries()) {
  try {
    if (act.type === 'text') await sock.sendMessage(jid, { text: act.text });
    else await sock.sendMessage(jid, { document: { url: act.path }, mimetype: act.mime, fileName: act.fileName, caption: act.caption });
    console.log(`[${i + 1}/${actions.length}] sent ${act.type === 'doc' ? act.fileName : 'text'}`);
  } catch (e) { console.log(`[${i + 1}/${actions.length}] FAILED ${act.fileName || 'text'}: ${e.message}`); }
  await sleep(SEND_DELAY);
}
console.log('ALL SENT'); await sleep(4000); process.exit(0);
