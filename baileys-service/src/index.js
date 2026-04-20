import pkg from 'whatsapp-web.js';
const { Client, LocalAuth } = pkg;
import express from 'express';
import QRCode from 'qrcode';

const PORT = Number(process.env.PORT || 3100);
const AUTH_DIR = process.env.AUTH_DIR || './auth_state';
const CHROME_PATH = process.env.CHROME_PATH || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';

const app = express();
app.use(express.json());

// ── State ──────────────────────────────────────────────────────

let currentQR = null;
let connectionStatus = 'disconnected'; // disconnected | connecting | connected

// ── WhatsApp client ────────────────────────────────────────────

const client = new Client({
  authStrategy: new LocalAuth({ dataPath: AUTH_DIR }),
  puppeteer: {
    headless: true,
    executablePath: CHROME_PATH,
    args: [
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--disable-dev-shm-usage',
      '--disable-gpu',
    ],
  },
});

client.on('qr', (qr) => {
  currentQR = qr;
  connectionStatus = 'connecting';
  console.log('New QR code generated — scan with WhatsApp');
});

client.on('ready', () => {
  currentQR = null;
  connectionStatus = 'connected';
  console.log('WhatsApp connected');
});

client.on('authenticated', () => {
  console.log('WhatsApp authenticated');
});

client.on('auth_failure', (msg) => {
  console.error('Auth failure:', msg);
  connectionStatus = 'disconnected';
  currentQR = null;
});

client.on('disconnected', (reason) => {
  console.log('Disconnected:', reason);
  connectionStatus = 'disconnected';
  currentQR = null;
  // Auto-reconnect
  setTimeout(() => client.initialize(), 5000);
});

// ── REST API ───────────────────────────────────────────────────

// GET /status
app.get('/status', (_req, res) => {
  res.json({ status: connectionStatus, hasQR: !!currentQR });
});

// GET /qr — returns QR code as data URL
app.get('/qr', async (_req, res) => {
  if (connectionStatus === 'connected') {
    return res.json({ status: 'connected', message: 'Already connected, no QR needed' });
  }
  if (!currentQR) {
    return res.status(202).json({ status: connectionStatus, message: 'QR not ready yet, try again' });
  }
  try {
    const png = await QRCode.toDataURL(currentQR, { width: 400 });
    res.json({ status: 'connecting', qr: png });
  } catch (err) {
    console.error('QR generation failed:', err);
    res.status(500).json({ error: 'QR generation failed' });
  }
});

// POST /send — send a WhatsApp message
// Body: { phone: "+79001234567", message: "Hello" }
app.post('/send', async (req, res) => {
  if (connectionStatus !== 'connected') {
    return res.status(503).json({ error: 'WhatsApp not connected' });
  }

  const { phone, message } = req.body;
  if (!phone || !message) {
    return res.status(400).json({ error: 'phone and message required' });
  }

  // Normalise phone: strip +, spaces, dashes; add @c.us
  const digits = phone.replace(/[^0-9]/g, '');
  if (digits.length < 7) {
    return res.status(400).json({ error: 'invalid phone number' });
  }
  const chatId = `${digits}@c.us`;

  try {
    const result = await client.sendMessage(chatId, message);
    console.log(`Message sent to ${chatId}: ${result.id._serialized}`);
    res.json({ ok: true, messageId: result.id._serialized });
  } catch (err) {
    console.error(`Send failed to ${chatId}:`, err);
    res.status(500).json({ error: err.message || 'send failed' });
  }
});

// POST /logout — disconnect and wipe session
app.post('/logout', async (_req, res) => {
  try {
    await client.logout();
    connectionStatus = 'disconnected';
    currentQR = null;
    res.json({ ok: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ── Start ──────────────────────────────────────────────────────

app.listen(PORT, () => {
  console.log(`WhatsApp Web service listening on port ${PORT}`);
  connectionStatus = 'connecting';
  client.initialize();
});
