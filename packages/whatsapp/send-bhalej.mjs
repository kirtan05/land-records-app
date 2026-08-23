// Sends the Bhalej "complete record" PDFs on WhatsApp — one message per survey.
// Each PDF is cover → integrated record (entry numbers back in RED) → every old hand-written
// નોંધ scan, one per page.
//
//   node packages/whatsapp/send-bhalej.mjs --to="gujarat land" --dry-run
//   node packages/whatsapp/send-bhalej.mjs --to=9198XXXXXXXX          # a phone number = a 1:1 chat
//   node packages/whatsapp/send-bhalej.mjs --to=1203634...@g.us       # an explicit jid
import makeWASocket, { useMultiFileAuthState, fetchLatestBaileysVersion, DisconnectReason } from '@whiskeysockets/baileys';
import pino from 'pino';
import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { REPO } from '../core/repo-root.mjs';

const ROOT = REPO;
const OUT = join(ROOT, 'output');
const AUTH_DIR = process.env.WA_AUTH_DIR || join(ROOT, 'packages/whatsapp/auth-fam');
const DRY = process.argv.includes('--dry-run');
const arg = (k, d) => { const v = process.argv.find((a) => a.startsWith(`--${k}=`)); return v ? v.split('=')[1] : d; };
const TO = arg('to', '');
// Restrict to specific surveys, e.g. --only=234,240. Default: every Bhalej record on disk.
const ONLY = arg('only', '').split(',').map((x) => x.trim()).filter(Boolean);
const DELAY = +arg('delay', process.argv.includes('--slow') ? 5000 : 2600);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── build the plan from what is actually on disk (never announce a file we don't have) ──
const surveys = readdirSync(OUT).filter((d) => d.startsWith('Bhalej_'))
  .map((tok) => {
    const dir = join(OUT, tok);
    const metaP = join(dir, `anyror_${tok}.json`);
    if (!existsSync(metaP)) return null;
    const meta = JSON.parse(readFileSync(metaP, 'utf8'));
    const survey = meta.survey || tok;
    const pdf = join(dir, `Bhalej_${survey.replace(/\//g, '-')}_FULL.pdf`);
    if (!existsSync(pdf)) return null;
    const eP = join(dir, 'entries/entries.json');
    const ent = existsSync(eP) ? JSON.parse(readFileSync(eP, 'utf8')) : { captured: [] };
    const cap = ent.captured || [];
    const num = (n) => parseInt(String(n).replace(/\D/g, ''), 10) || 0;
    const withScan = cap.filter((c) => c.files?.length).map((c) => c.number).sort((a, b) => num(a) - num(b));
    const missing = cap.filter((c) => !c.files?.length).map((c) => c.number).sort((a, b) => num(a) - num(b));
    return { tok, survey, pdf, meta, withScan, missing, deeds: (meta.deeds || []).length };
  }).filter(Boolean)
  .filter((s) => !ONLY.length || ONLY.some((o) => o.toLowerCase() === String(s.survey).toLowerCase()))
  .sort((a, b) => a.survey.localeCompare(b.survey, 'en', { numeric: true }));

if (!surveys.length) { console.error('no Bhalej_*_FULL.pdf found in output/ — run the fetch + combine first'); process.exit(1); }

const text = (t) => ({ type: 'text', text: t });
const doc = (path, caption, fileName) => ({ type: 'doc', path, caption, fileName });
const actions = [];

// Never promise scans that this batch doesn't contain.
const anyScans = surveys.some((s) => s.withScan.length);
actions.push(text(
  `📄 *ભાલેજ — સંપૂર્ણ જમીન રેકોર્ડ / Bhalej land records*\n` +
  `ગામ ભાલેજ · તાલુકો ઉમરેઠ · જિલ્લો આણંદ · સરવે નં. ${surveys.map((s) => s.survey).join(', ')}\n\n` +
  (anyScans
    ? `બંને વાત સુધારી છે:\n` +
      `① *જૂની નોંધની વિગત* — હવે દરેક જૂની (હાથે લખેલી) નોંધની સ્કૅન નકલ એ જ ફાઇલમાં જોડેલ છે, ` +
      `એક નોંધ = એક પાનું. જૂના VF-6 માં એક-એક નોંધ નંબર નાખવાની જરૂર નથી.\n` +
      `② *લાલ અક્ષર* — નોંધ નંબરોની યાદીમાં જૂની નોંધો ફરીથી *લાલ* + બોલ્ડ + અન્ડરલાઇન છપાય છે.\n\n` +
      `દરેક ફાઇલમાં: પહેલું પાનું = અંદર શું છે તેની યાદી → સંકલિત સર્વે રેકોર્ડ → જૂની નોંધોની સ્કૅન નકલ.`
    : `આ સરવે નંબરોમાં *જૂની હાથે લખેલી નોંધની સ્કૅન નકલ AnyRoR પર છે જ નહીં* — તપાસી લીધું છે, ` +
      `જૂના VF-6 માં પણ સાઇટ કહે છે કે વિગત મળેલ નથી. તે નોંધ માટે મામલતદાર કચેરી જ એકમાત્ર રસ્તો છે.\n\n` +
      `બાકીની બધી નોંધ કમ્પ્યુટરાઇઝ્ડ છે અને તેની પૂરી લખાણ-વિગત ફાઇલમાં જ છે.\n` +
      `દરેક ફાઇલમાં: પહેલું પાનું = અંદર શું છે તેની યાદી → સંકલિત સર્વે રેકોર્ડ.`)));

for (const s of surveys) {
  const m = s.meta;
  const bits = [m.total_area ? `ક્ષેત્રફળ ${m.total_area}` : '', m.total_assessment ? `આકાર ${m.total_assessment}` : '', m.tenure || ''].filter(Boolean).join(' · ');
  actions.push(text(
    `━━━━━━━━━━━━━━\n📍 *સરવે નં. ${s.survey}* — ભાલેજ${bits ? `\n${bits}` : ''}\n` +
    `જૂની નોંધ સ્કૅન જોડેલ: *${s.withScan.length}* ${s.withScan.length ? `(${s.withScan.join(', ')})` : ''}` +
    (s.missing.length ? `\n⚠️ AnyRoR પર નથી: ${s.missing.join(', ')} — આ નોંધો સાઇટ પર સ્કૅન થયેલ જ નથી, મામલતદાર કચેરીમાં જ મળશે.` : '') +
    (s.deeds ? `\n📑 સબ-રજીસ્ટ્રાર દસ્તાવેજની વિગત પણ અંદર છે (સ્કૅન નકલ AnyRoR પરથી ઉતરતી નથી).` : '')));
  actions.push(doc(s.pdf, `📄 ભાલેજ ${s.survey} — સંપૂર્ણ રેકોર્ડ (${s.withScan.length} જૂની નોંધ સ્કૅન સાથે)`,
    `Bhalej_${s.survey.replace(/\//g, '-')}_Complete.pdf`));
}

console.log(`PLAN → ${TO || '(no recipient)'} | ${actions.length} messages (${actions.filter((a) => a.type === 'doc').length} PDFs)`);
actions.forEach((a, i) => console.log(`  ${i + 1}. ${a.type === 'doc' ? `[PDF] ${a.fileName}` : a.text.split('\n')[0].slice(0, 80)}`));
if (DRY) { console.log('\n--dry-run: nothing sent.'); process.exit(0); }
if (!TO) { console.error('\nNo recipient. Pass --to=<number|jid|group name>.'); process.exit(1); }

// ── resolve the recipient ───────────────────────────────────────────────────────────
let jid = '';
if (TO.includes('@')) jid = TO;
else if (/^[0-9\s+-]{10,}$/.test(TO)) jid = `${TO.replace(/\D/g, '')}@s.whatsapp.net`;
else {
  const groups = JSON.parse(readFileSync(join(ROOT, 'packages/whatsapp/groups.json'), 'utf8'));
  const m = groups.filter((g) => (g.name || '').toLowerCase().includes(TO.toLowerCase()));
  if (m.length !== 1) { console.error(m.length ? `Ambiguous group "${TO}": ${m.map((g) => g.name).join(' | ')}` : `No group matches "${TO}"`); process.exit(1); }
  jid = m[0].id;
  console.log(`group "${m[0].name}" → ${jid}`);
}
console.log('target jid:', jid);

const { state: auth, saveCreds } = await useMultiFileAuthState(AUTH_DIR);
const { version } = await fetchLatestBaileysVersion();
const sock = makeWASocket({ version, auth, logger: pino({ level: 'silent' }), browser: ['Bhalej Records', 'Chrome', '1.0'], syncFullHistory: false });
sock.ev.on('creds.update', saveCreds);
await new Promise((resolve, reject) => {
  const t = setTimeout(() => reject(new Error('no connection within 45s')), 45000);
  sock.ev.on('connection.update', (u) => {
    if (u.qr) { clearTimeout(t); reject(new Error(`NEEDS_RESCAN — ${AUTH_DIR} is not authenticated; run node packages/whatsapp/login.mjs`)); }
    if (u.connection === 'open') { clearTimeout(t); resolve(); }
    if (u.connection === 'close') {
      const c = u.lastDisconnect?.error?.output?.statusCode;
      if (c === DisconnectReason.loggedOut) { clearTimeout(t); reject(new Error('LOGGED_OUT — re-pair the auth store')); }
    }
  });
});
console.log('connected; sending in 3s…');
await sleep(3000);

let ok = 0, fail = 0;
for (const [i, a] of actions.entries()) {
  try {
    if (a.type === 'text') await sock.sendMessage(jid, { text: a.text });
    else await sock.sendMessage(jid, { document: { url: a.path }, mimetype: 'application/pdf', fileName: a.fileName, caption: a.caption });
    ok++; console.log(`[${i + 1}/${actions.length}] sent ${a.type === 'doc' ? a.fileName : 'text'}`);
  } catch (e) { fail++; console.log(`[${i + 1}/${actions.length}] FAILED ${a.fileName || 'text'}: ${e.message}`); }
  await sleep(DELAY);
}
console.log(`DONE — ok ${ok}, failed ${fail}`);
await sleep(4000);
process.exit(fail ? 1 : 0);
