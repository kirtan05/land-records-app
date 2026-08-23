// READ-ONLY: list the participants of one or more WhatsApp groups, with whatever name WhatsApp
// knows for each (contact name / push name / group subject owner). Sends nothing.
//   node packages/whatsapp/list-group-participants.mjs 1203630XXXXXXXXXX@g.us
//   node packages/whatsapp/list-group-participants.mjs --name="fam" --name="gujarat land"
import makeWASocket, { useMultiFileAuthState, fetchLatestBaileysVersion, DisconnectReason } from '@whiskeysockets/baileys';
import pino from 'pino';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { REPO } from '../core/repo-root.mjs';

const ROOT = REPO;
const AUTH_DIR = process.env.WA_AUTH_DIR || join(ROOT, 'packages/whatsapp/auth-fam');
const argv = process.argv.slice(2);
const names = argv.filter((a) => a.startsWith('--name=')).map((a) => a.split('=')[1]);
let jids = argv.filter((a) => a.endsWith('@g.us'));

const groups = JSON.parse(readFileSync(join(ROOT, 'packages/whatsapp/groups.json'), 'utf8'));
for (const n of names) {
  const m = groups.filter((g) => (g.name || '').toLowerCase().includes(n.toLowerCase()));
  if (!m.length) console.error(`no group matches "${n}"`);
  m.forEach((g) => jids.push(g.id));
}
jids = [...new Set(jids)];
if (!jids.length) { console.error('pass a @g.us jid or --name="part of group name"'); process.exit(64); }

const nameOf = new Map();          // jid -> best known human name
const note = (jid, n) => { if (jid && n && !nameOf.has(jid)) nameOf.set(jid, n); };

const { state, saveCreds } = await useMultiFileAuthState(AUTH_DIR);
const { version } = await fetchLatestBaileysVersion();
const sock = makeWASocket({
  version, auth: state, logger: pino({ level: 'silent' }),
  browser: ['Roster', 'Chrome', '1.0'], syncFullHistory: false,
});
sock.ev.on('creds.update', saveCreds);
sock.ev.on('contacts.set', ({ contacts }) => contacts?.forEach((c) => note(c.id, c.name || c.notify || c.verifiedName)));
sock.ev.on('contacts.upsert', (cs) => cs?.forEach((c) => note(c.id, c.name || c.notify || c.verifiedName)));
sock.ev.on('contacts.update', (cs) => cs?.forEach((c) => note(c.id, c.name || c.notify || c.verifiedName)));
sock.ev.on('messaging-history.set', ({ contacts }) => contacts?.forEach((c) => note(c.id, c.name || c.notify || c.verifiedName)));

await new Promise((resolve, reject) => {
  const t = setTimeout(() => reject(new Error('no connection within 45s')), 45000);
  sock.ev.on('connection.update', (u) => {
    if (u.qr) { clearTimeout(t); reject(new Error(`NEEDS_RESCAN — ${AUTH_DIR} is not authenticated`)); }
    if (u.connection === 'open') { clearTimeout(t); resolve(); }
    if (u.connection === 'close' && u.lastDisconnect?.error?.output?.statusCode === DisconnectReason.loggedOut) { clearTimeout(t); reject(new Error('LOGGED_OUT')); }
  });
});
await new Promise((r) => setTimeout(r, 6000));   // let the contact sync land

const out = {};
for (const jid of jids) {
  try {
    const md = await sock.groupMetadata(jid);
    console.log(`\n══════ ${md.subject}  (${md.participants.length} participants)  ${jid}`);
    const rows = md.participants.map((p) => {
      const id = p.id || p.jid;
      const num = (id || '').split('@')[0].split(':')[0];
      return { number: num, name: nameOf.get(id) || '', admin: p.admin || '' };
    }).sort((a, b) => (b.name ? 1 : 0) - (a.name ? 1 : 0));
    rows.forEach((r) => console.log(`  ${r.number.padEnd(15)} ${r.admin.padEnd(11)} ${r.name}`));
    out[md.subject] = rows;
  } catch (e) { console.log(`\n${jid}: FAILED ${e.message}`); }
}
writeFileSync(join(ROOT, 'packages/whatsapp/participants.json'), JSON.stringify(out, null, 1));
console.log('\n→ packages/whatsapp/participants.json');
process.exit(0);
