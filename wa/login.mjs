// Connect to WhatsApp via Baileys: show QR (terminal + wa/qr.png), then list groups.
import makeWASocket, { useMultiFileAuthState, DisconnectReason, fetchLatestBaileysVersion } from '@whiskeysockets/baileys';
import qrcodeTerminal from 'qrcode-terminal';
import QRCode from 'qrcode';
import pino from 'pino';
import { writeFileSync } from 'node:fs';

const BASE = '/home/kirtan/Desktop/projects/irmsc/wa';
const { state, saveCreds } = await useMultiFileAuthState(BASE + '/auth');
const { version } = await fetchLatestBaileysVersion();

function start() {
  const sock = makeWASocket({ version, auth: state, logger: pino({ level: 'silent' }), browser: ['Bharoda Records', 'Chrome', '1.0'], syncFullHistory: false });
  sock.ev.on('creds.update', saveCreds);
  sock.ev.on('connection.update', async (u) => {
    const { connection, lastDisconnect, qr } = u;
    if (qr) {
      console.log('\n===== SCAN THIS QR — WhatsApp ▸ Settings ▸ Linked Devices ▸ Link a device =====\n');
      qrcodeTerminal.generate(qr, { small: true });
      try { await QRCode.toFile(BASE + '/qr.png', qr, { width: 520, margin: 2 }); console.log('\n(also saved wa/qr.png — open it if the terminal QR is hard to scan)'); } catch {}
    }
    if (connection === 'open') {
      console.log('\n✅ CONNECTED as', sock.user?.id);
      try {
        const groups = await sock.groupFetchAllParticipating();
        const list = Object.values(groups).map((g) => ({ id: g.id, name: g.subject || '(unnamed group)', size: g.participants?.length || 0 }));
        list.sort((a, b) => (a.name || '').localeCompare(b.name || ''));
        writeFileSync(BASE + '/groups.json', JSON.stringify(list, null, 2));
        console.log('\n===== YOUR GROUPS =====');
        list.forEach((g, i) => console.log(`${String(i + 1).padStart(2)}. ${g.name}  (${g.size} members)`));
      } catch (e) { console.log('group fetch error:', e.message); }
      console.log('\nGROUPS_SAVED — creds stored in wa/auth (scan once). You can close this.');
      setTimeout(() => process.exit(0), 2000);
    }
    if (connection === 'close') {
      const code = lastDisconnect?.error?.output?.statusCode;
      if (code === DisconnectReason.loggedOut) { console.log('LOGGED_OUT — delete wa/auth and re-run to re-scan.'); process.exit(1); }
      console.log('connection closed, reconnecting…'); start();
    }
  });
}
start();
