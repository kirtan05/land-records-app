// Read-only: connect with a given auth dir, report status, send nothing.
import makeWASocket, { useMultiFileAuthState, DisconnectReason, fetchLatestBaileysVersion } from '@whiskeysockets/baileys';
import pino from 'pino';
const DIR = process.argv[2];
const { state, saveCreds } = await useMultiFileAuthState(DIR);
const { version } = await fetchLatestBaileysVersion();
const sock = makeWASocket({ version, auth: state, logger: pino({ level: 'silent' }), browser: ['bmstrack', 'Chrome', '1.0'], syncFullHistory: false });
sock.ev.on('creds.update', saveCreds);
const done = (m, c) => { console.log(m); setTimeout(() => process.exit(c), 400); };
const guard = setTimeout(() => done('TIMEOUT', 2), 30000);
sock.ev.on('connection.update', (u) => {
  const { connection, lastDisconnect, qr } = u;
  if (qr) { clearTimeout(guard); done('NEEDS_RESCAN', 3); }
  if (connection === 'open') { clearTimeout(guard); done(`VALID — ${sock.user?.id} (${sock.user?.name || ''})`, 0); }
  if (connection === 'close') {
    const code = lastDisconnect?.error?.output?.statusCode;
    clearTimeout(guard);
    done(code === DisconnectReason.loggedOut ? 'LOGGED_OUT' : `CLOSED code=${code}`, 1);
  }
});
