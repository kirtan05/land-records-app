// Sends the OLD SCANNED VF-7/12 combined PDFs to the family WhatsApp group (one per survey).
//   node wa/send-vf712.mjs --dry-run
//   node wa/send-vf712.mjs --group="gujarat" --slow
import makeWASocket, { useMultiFileAuthState, fetchLatestBaileysVersion, DisconnectReason } from '@whiskeysockets/baileys';
import pino from 'pino';
import { readFileSync, existsSync } from 'node:fs';
import { join, basename } from 'node:path';

const ROOT = '/home/kirtan/Desktop/projects/irmsc';
const OUT = join(ROOT, 'output');
const DRY = process.argv.includes('--dry-run');
const SEND_DELAY = process.argv.includes('--slow') ? 5000 : (parseInt((process.argv.find((a) => a.startsWith('--delay=')) || '').split('=')[1]) || 2300);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const groupArg = (process.argv.find((a) => a.startsWith('--group=')) || '').split('=')[1] || process.argv.find((a) => a.endsWith('@g.us'));

const order = JSON.parse(readFileSync(join(ROOT, 'survey-input.json'), 'utf8')).filter((r) => r.matched).map((r) => r.normalized);
const state = JSON.parse(readFileSync(join(OUT, '_vf712_state.json'), 'utf8'));
const rangeOf = (periods) => { const ys = periods.flatMap((p) => p.split('-').map(Number)); return `${Math.min(...ys)}–${Math.max(...ys)}`; };
const doc = (path, caption, fileName) => ({ type: 'doc', path, caption, fileName: fileName || basename(path) });
const text = (t) => ({ type: 'text', text: t });

const actions = [];
let totalDocs = 0;
for (const k of order) { const v = state[k]; if (v?.kept) totalDocs += v.kept; }
actions.push(text(`📜 *Bharoda — Old Scanned VF-7/12 (જૂના સ્કેન કરેલ ગા.ન. ૭/૧૨)*\nDistrict Anand · Taluka Umreth · Village Bharoda\n${order.filter((k) => state[k]?.kept).length} survey numbers · ${totalDocs} historic scanned 7/12 records.\nEach PDF runs newest year → oldest, with a year label before every period.`));
for (const k of order) {
  const v = state[k];
  if (!v?.combined || !existsSync(v.combined)) { actions.push(text(`⚠️ Survey ${k}: no VF-7/12 scans available.`)); continue; }
  const range = rangeOf(v.periods);
  actions.push(text(`━━━━━━━━━━━━━━\n📜 *Survey No ${k}* — VF-7/12 (old scanned)\n${v.kept} scanned document(s) · years ${range}`));
  actions.push(doc(v.combined, `📜 VF-7/12 ${k} — Bharoda (${range}, ${v.kept} docs)`, `VF-7-12_Bharoda_${v.token}.pdf`));
}
actions.push(text(`✅ Old VF-7/12 scanned records sent — ${order.filter((k) => state[k]?.kept).length} surveys · ${totalDocs} documents.`));

const docCount = actions.filter((a) => a.type === 'doc').length;
console.log(`PLAN: ${actions.length} messages | ${docCount} combined PDFs · ${actions.length - docCount} text | ~${Math.round(actions.length * SEND_DELAY / 1000 / 60)} min at ${SEND_DELAY}ms`);
actions.filter((a) => a.type === 'doc').forEach((a) => console.log('   •', a.caption));
if (DRY) process.exit(0);

if (!groupArg) { console.error('No group specified. Use --group="name" or a JID.'); process.exit(1); }
const groups = existsSync(join(ROOT, 'wa/groups.json')) ? JSON.parse(readFileSync(join(ROOT, 'wa/groups.json'), 'utf8')) : [];
let jid = groupArg.endsWith('@g.us') ? groupArg : null;
if (!jid) {
  const m = groups.filter((g) => g.name.toLowerCase().includes(groupArg.toLowerCase()));
  if (m.length === 1) jid = m[0].id;
  else { console.error(m.length ? 'Ambiguous group, matches: ' + m.map((g) => g.name).join(' | ') : 'No group matches: ' + groupArg); process.exit(1); }
}
console.log('target group jid:', jid);

const { state: auth, saveCreds } = await useMultiFileAuthState(join(ROOT, 'wa/auth'));
const { version } = await fetchLatestBaileysVersion();
const sock = makeWASocket({ version, auth, logger: pino({ level: 'silent' }), browser: ['Bharoda Records', 'Chrome', '1.0'] });
sock.ev.on('creds.update', saveCreds);
await new Promise((resolve, reject) => {
  sock.ev.on('connection.update', (u) => {
    if (u.qr) reject(new Error('Not logged in — run `node wa/login.mjs` and scan first.'));
    if (u.connection === 'open') resolve();
    if (u.connection === 'close') { const c = u.lastDisconnect?.error?.output?.statusCode; if (c === DisconnectReason.loggedOut) reject(new Error('Logged out — re-run login.')); }
  });
});
console.log('connected; sending in 3s…'); await sleep(3000);

let ok = 0, fail = 0;
for (const [i, act] of actions.entries()) {
  try {
    if (act.type === 'text') await sock.sendMessage(jid, { text: act.text });
    else await sock.sendMessage(jid, { document: { url: act.path }, mimetype: 'application/pdf', fileName: act.fileName, caption: act.caption });
    ok++; console.log(`[${i + 1}/${actions.length}] sent ${act.type === 'doc' ? act.fileName : 'text'}`);
  } catch (e) { fail++; console.log(`[${i + 1}/${actions.length}] FAILED ${act.fileName || 'text'}: ${e.message}`); }
  await sleep(SEND_DELAY);
}
console.log(`ALL SENT — ok: ${ok}, failed: ${fail}`); await sleep(4000); process.exit(0);
