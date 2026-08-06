// Read-only WhatsApp auth validity check: connect, report status, exit. Sends nothing.
import makeWASocket, { useMultiFileAuthState, DisconnectReason, fetchLatestBaileysVersion } from '@whiskeysockets/baileys';
import pino from 'pino';

const BASE = '/home/kirtan/Desktop/projects/irmsc/wa';
const { state, saveCreds } = await useMultiFileAuthState(BASE + '/auth');
const { version } = await fetchLatestBaileysVersion();

const sock = makeWASocket({ version, auth: state, logger: pino({ level: 'silent' }), browser: ['Bharoda Records', 'Chrome', '1.0'], syncFullHistory: false });
sock.ev.on('creds.update', saveCreds);

const done = (msg, code) => { console.log(msg); setTimeout(() => process.exit(code), 500); };
const guard = setTimeout(() => done('RESULT: TIMEOUT — no open/close within 25s (network or handshake stalled).', 2), 25000);

sock.ev.on('connection.update', (u) => {
  const { connection, lastDisconnect, qr } = u;
  if (qr) { clearTimeout(guard); done('RESULT: NEEDS_RESCAN — server issued a QR, meaning the stored session is not authenticated.', 3); }
  if (connection === 'open') { clearTimeout(guard); done(`RESULT: VALID — connected as ${sock.user?.id} (${sock.user?.name || ''}).`, 0); }
  if (connection === 'close') {
    const code = lastDisconnect?.error?.output?.statusCode;
    if (code === DisconnectReason.loggedOut) { clearTimeout(guard); done('RESULT: LOGGED_OUT — session revoked; delete wa/auth and re-scan.', 1); }
    // transient close (restartRequired etc.) — report but do not loop
    clearTimeout(guard); done(`RESULT: CLOSED code=${code} — transient/needs restart; re-run login.mjs to confirm.`, 4);
  }
});
