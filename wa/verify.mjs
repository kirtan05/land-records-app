// Read-only: check whether numbers are registered on WhatsApp. Sends nothing.
import makeWASocket, { useMultiFileAuthState, fetchLatestBaileysVersion } from '@whiskeysockets/baileys';
import pino from 'pino';
const AUTH = process.env.WA_AUTH_DIR || '/home/kirtan/Desktop/projects/irmsc/wa/auth-fam';
const nums = process.argv.slice(2);
const { state, saveCreds } = await useMultiFileAuthState(AUTH);
const { version } = await fetchLatestBaileysVersion();
const sock = makeWASocket({ version, auth: state, logger: pino({ level: 'silent' }), browser: ['wa-verify','Chrome','1.0'], syncFullHistory: false });
sock.ev.on('creds.update', saveCreds);
const guard = setTimeout(() => { console.log('TIMEOUT'); process.exit(2); }, 40000);
sock.ev.on('connection.update', async (u) => {
  if (u.connection !== 'open') return;
  clearTimeout(guard);
  for (const n of nums) {
    try {
      const r = await sock.onWhatsApp(n.replace(/[^0-9]/g,''));
      if (r && r.length && r[0].exists) console.log(`REGISTERED  ${n} -> ${r[0].jid}`);
      else console.log(`NOT_ON_WHATSAPP  ${n}`);
    } catch (e) { console.log(`ERROR  ${n}: ${e?.message || e}`); }
  }
  setTimeout(() => process.exit(0), 800);
});
