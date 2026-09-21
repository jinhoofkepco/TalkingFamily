import { resolve } from 'node:path';
import { Store } from './store.js';
import { TelegramRelay } from './telegram.js';
import { createHttpServer } from './http.js';
import { createPush, PushWorker } from './push.js';

const tokens = { child: process.env.CHILD_DEVICE_TOKEN || '', guardian: process.env.GUARDIAN_DEVICE_TOKEN || '' };
if (Object.values(tokens).some(t => t.length < 32 || t.length > 256 || /\s/.test(t)) || tokens.child === tokens.guardian) {
  console.error('Set distinct CHILD_DEVICE_TOKEN and GUARDIAN_DEVICE_TOKEN values of 32–256 characters.');
  process.exit(1);
}
const botTokens = { child: process.env.CHILD_BOT_TOKEN || '', guardian: process.env.GUARDIAN_BOT_TOKEN || '' };
if (Boolean(botTokens.child) !== Boolean(botTokens.guardian) || (botTokens.child && botTokens.child === botTokens.guardian)) {
  console.error('Configure two different Telegram bot tokens, or leave both blank.');
  process.exit(1);
}
if (Object.values(botTokens).some(t => t && !/^\d+:[A-Za-z0-9_-]+$/.test(t))) {
  console.error('A Telegram bot token has an invalid format.'); process.exit(1);
}
process.umask(0o077);
const store = new Store(resolve(process.env.DATABASE_PATH || './data/family.sqlite'));
const relay = new TelegramRelay(store, { tokens: botTokens });
let push;
try { push = await createPush(process.env.FIREBASE_SERVICE_ACCOUNT); }
catch { console.error('Firebase could not be initialized. Check the service account file and optional dependency.'); store.close(); process.exit(1); }
const pushWorker = new PushWorker(store, push);
const allowedOrigins = (process.env.ALLOWED_ORIGINS || '').split(',').map(s => s.trim()).filter(Boolean);
const server = createHttpServer({ store, relay, push, tokens, allowedOrigins });
const port = Number(process.env.PORT || 8787);
if (!Number.isInteger(port) || port < 1 || port > 65535) { console.error('PORT must be between 1 and 65535.'); process.exit(1); }
try { relay.start(); pushWorker.start(); }
catch { console.error('Unable to start Telegram worker. Ensure only one server uses this database.'); store.close(); process.exit(1); }
server.listen(port, process.env.HOST || '127.0.0.1', () => console.info(`Family server listening on port ${port}; Telegram ${relay.configured ? 'configured' : 'unconfigured'}.`));
let stopping = false;
async function shutdown() {
  if (stopping) return;
  stopping = true;
  const closed = new Promise(resolve => server.close(resolve));
  server.closeIdleConnections();
  await Promise.all([relay.stop(), pushWorker.stop(), closed]);
  store.close();
}
process.once('SIGINT', shutdown);
process.once('SIGTERM', shutdown);
