// Quick WhatsApp auth check: connects with saved creds and reports AUTHED / NEEDS_SCAN.
import makeWASocket, { useMultiFileAuthState, fetchLatestBaileysVersion, DisconnectReason } from '@whiskeysockets/baileys';
import pino from 'pino';

const { state, saveCreds } = await useMultiFileAuthState('/home/kirtan/Desktop/projects/irmsc/wa/auth');
const { version } = await fetchLatestBaileysVersion();
const sock = makeWASocket({ version, auth: state, logger: pino({ level: 'silent' }), browser: ['Bharoda Records', 'Chrome', '1.0'] });
sock.ev.on('creds.update', saveCreds);
let done = false;
const finish = (msg, code) => { if (done) return; done = true; console.log(msg); setTimeout(() => process.exit(code), 500); };
sock.ev.on('connection.update', (u) => {
  if (u.qr) finish('NEEDS_SCAN — WhatsApp login expired; run `node wa/login.mjs` to re-scan.', 2);
  if (u.connection === 'open') finish('AUTHED — WhatsApp login is valid, ready to send.', 0);
  if (u.connection === 'close') { const c = u.lastDisconnect?.error?.output?.statusCode; if (c === DisconnectReason.loggedOut) finish('NEEDS_SCAN — logged out; run `node wa/login.mjs`.', 2); }
});
setTimeout(() => finish('TIMEOUT — could not confirm within 25s (network?). Try the send; if it asks for QR, run login.', 3), 25000);
