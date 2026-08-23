// Generic one-shot WhatsApp text sender, for alerts from any project.
// Lives here so Node resolves irmsc/node_modules and sits beside the auth
// store it uses. Unrelated to send.mjs (that one ships the Bharoda records).
//
//   node notify.mjs <jid|number> [<jid|number> ...] --text "message"
//   echo "message" | node notify.mjs 91XXXXXXXXXX
//
// Defaults to the auth-fam store, which is the one currently authenticated.
// Override with WA_AUTH_DIR.
//
// Exit 0 only if every recipient got it, so a caller can fall back to another
// channel on non-zero.

import makeWASocket, {
  useMultiFileAuthState, DisconnectReason, fetchLatestBaileysVersion,
} from '@whiskeysockets/baileys';
import pino from 'pino';
import { readFileSync } from 'node:fs';
import { REPO } from '../src/repo-root.mjs';

const AUTH_DIR = process.env.WA_AUTH_DIR
  || REPO+'/wa/auth-fam';

const argv = process.argv.slice(2);
const textIdx = argv.indexOf('--text');
let text = textIdx >= 0 ? argv[textIdx + 1] : null;
const targets = (textIdx >= 0 ? argv.slice(0, textIdx) : argv).filter(Boolean);

if (text === null) {
  try { text = readFileSync(0, 'utf8'); } catch { text = ''; }
}
text = (text || '').trim();

if (!targets.length || !text) {
  console.error('usage: node notify.mjs <jid|number>... --text "msg"   (or pipe text on stdin)');
  process.exit(64);
}

// Accept a bare number, a user jid, or a group jid.
const toJid = (t) => (t.includes('@') ? t : `${t.replace(/[^0-9]/g, '')}@s.whatsapp.net`);

const { state, saveCreds } = await useMultiFileAuthState(AUTH_DIR);
const { version } = await fetchLatestBaileysVersion();

const sock = makeWASocket({
  version,
  auth: state,
  logger: pino({ level: 'silent' }),
  browser: ['wa-notify', 'Chrome', '1.0'],
  syncFullHistory: false,
});
sock.ev.on('creds.update', saveCreds);

const finish = (code) => setTimeout(() => process.exit(code), 600);

// An alert path must never hang on a stalled socket.
const guard = setTimeout(() => {
  console.error('TIMEOUT: no connection within 40s');
  finish(2);
}, 40000);

sock.ev.on('connection.update', async (u) => {
  const { connection, lastDisconnect, qr } = u;

  if (qr) {
    clearTimeout(guard);
    console.error(`NEEDS_RESCAN: ${AUTH_DIR} is not authenticated`);
    return finish(3);
  }

  if (connection === 'close') {
    const code = lastDisconnect?.error?.output?.statusCode;
    clearTimeout(guard);
    console.error(code === DisconnectReason.loggedOut
      ? `LOGGED_OUT: ${AUTH_DIR} was revoked; re-pair it`
      : `CLOSED code=${code}`);
    return finish(1);
  }

  if (connection !== 'open') return;
  clearTimeout(guard);

  let failed = 0;
  for (const t of targets) {
    const jid = toJid(t);
    try {
      await sock.sendMessage(jid, { text });
      console.log(`SENT -> ${jid}`);
    } catch (e) {
      failed++;
      console.error(`FAILED -> ${jid}: ${e?.message || e}`);
    }
  }
  await new Promise((r) => setTimeout(r, 1200));  // let the socket flush
  finish(failed ? 1 : 0);
});
