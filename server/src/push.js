import { readFile } from 'node:fs/promises';

export async function createPush(serviceAccountPath) {
  if (!serviceAccountPath) return null;
  const [{ initializeApp, cert }, { getMessaging }] = await Promise.all([import('firebase-admin/app'), import('firebase-admin/messaging')]);
  const serviceAccount = JSON.parse(await readFile(serviceAccountPath, 'utf8'));
  const app = initializeApp({ credential: cert(serviceAccount) });
  return { send: (token, eventId, kind) => getMessaging(app).send({
    token,
    // No coordinates, message text, child name, or bot/device tokens in push payloads.
    data: { eventId, kind },
    notification: { title: '우리 가족', body: '새 가족 소식이 도착했어요' },
    android: { priority: 'high', ttl: 5 * 60_000 },
  }) };
}

export class PushWorker {
  constructor(store, push, log = console) { this.store = store; this.push = push; this.log = log; this.busy = false; }
  async tick() {
    if (!this.push || this.busy) return;
    this.busy = true;
    try {
      const job = this.store.pushJob();
      if (!job) return;
      try { await this.push.send(job.token, job.event_id, this.store.get(job.event_id).kind); this.store.pushDone(job.event_id); }
      catch (error) {
        if (['messaging/registration-token-not-registered', 'messaging/invalid-registration-token'].includes(error.code)) this.store.removePushToken(job.role, job.token);
        this.store.pushFailed(job.event_id);
        this.log.warn('Push notification delayed; the event remains available in the app.');
      }
    } finally { this.busy = false; }
  }
  start() { if (this.push) { this.timer = setInterval(() => { this.inFlight = this.tick(); }, 1000); this.timer.unref(); } }
  async stop() { clearInterval(this.timer); await this.inFlight; }
}
