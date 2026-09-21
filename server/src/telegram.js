import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { envelope, peerOf } from './protocol.js';

/** One long-poll consumer per bot. Database lease prevents another process consuming the same DB. */
export class TelegramRelay {
  constructor(store, { tokens, apiBase = 'https://api.telegram.org', fetchImpl = fetch, log = console, pollTimeout = 20, sendInterval = 1250 }) {
    this.store = store;
    this.tokens = tokens;
    this.apiBase = apiBase.replace(/\/$/, '');
    const url = new URL(this.apiBase);
    if (url.protocol !== 'https:' && !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)) throw new Error('Telegram API must use HTTPS, except loopback test servers.');
    this.fetchImpl = fetchImpl;
    this.log = log;
    this.pollTimeout = pollTimeout;
    this.sendInterval = sendInterval;
    this.configured = Boolean(tokens.child && tokens.guardian);
    this.ready = false;
    this.identities = {};
    this.owner = randomUUID();
    this.controller = new AbortController();
    this.tasks = [];
  }
  async call(role, method, payload = {}) {
    const response = await this.fetchImpl(`${this.apiBase}/bot${this.tokens[role]}/${method}`, {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload),
      signal: AbortSignal.any([this.controller.signal, AbortSignal.timeout((this.pollTimeout + 10) * 1000)]),
    });
    let data;
    try { data = await response.json(); } catch { throw new Error('telegram_invalid_response'); }
    if (!response.ok || !data.ok) {
      const error = new Error('telegram_request_failed');
      error.status = data.error_code || response.status;
      error.retryAfter = Math.min(3600, Math.max(1, Number(data.parameters?.retry_after) || 0));
      throw error;
    }
    return data.result;
  }
  async initialize() {
    for (const role of ['child', 'guardian']) {
      const identity = await this.call(role, 'getMe');
      if (!identity?.is_bot || !Number.isSafeInteger(identity.id) || !/^[a-zA-Z0-9_]{5,32}$/.test(identity.username)) throw new Error('telegram_invalid_bot_identity');
      const webhook = await this.call(role, 'getWebhookInfo');
      if (webhook.url) throw new Error('telegram_existing_webhook');
      this.identities[role] = identity;
    }
    if (this.identities.child.id === this.identities.guardian.id) throw new Error('telegram_bots_must_be_distinct');
    this.ready = true;
  }
  async sendOne(role) {
    const event = this.store.pending(role);
    if (!event) return false;
    try {
      await this.call(role, 'sendMessage', { chat_id: `@${this.identities[peerOf(role)].username}`, text: envelope(event), disable_notification: true, protect_content: true });
      // Only the receiving bot's validated getUpdates message confirms delivery.
      this.store.attempt(event.id, 30_000);
    } catch (error) {
      this.store.attempt(event.id, Math.max((error.retryAfter || 0) * 1000, Math.min(60_000, 2000 * 2 ** Math.min(5, this.store.attempts(event.id)))));
      if (!this.controller.signal.aborted) this.log.warn(`Telegram send delayed (${error.status || 'network'}).`);
    }
    return true;
  }
  async pollOnce(role) {
    const updates = await this.call(role, 'getUpdates', { offset: this.store.offset(role), timeout: this.pollTimeout, limit: 100, allowed_updates: ['message'] });
    if (!Array.isArray(updates)) throw new Error('telegram_invalid_updates');
    for (const update of updates.sort((a, b) => a.update_id - b.update_id)) {
      if (!Number.isSafeInteger(update.update_id) || update.update_id < 0) continue;
      const message = update.message;
      const peerId = this.identities[peerOf(role)].id;
      const trusted = message?.from?.is_bot === true && message.from.id === peerId && message.chat?.type === 'private' && message.chat.id === peerId && !message.forward_origin && typeof message.text === 'string' && message.text.length <= 4096;
      this.store.receive(role, update.update_id, trusted ? message.text : '');
    }
    return updates.length;
  }
  async pause(ms) { try { await delay(ms, undefined, { signal: this.controller.signal }); } catch {} }
  async receiveLoop(role) {
    while (!this.controller.signal.aborted) {
      try { const count = await this.pollOnce(role); if (!count) await this.pause(200); }
      catch (error) { if (!this.controller.signal.aborted) this.log.warn(`Telegram receive delayed (${error.status || 'network'}).`); await this.pause(3000); }
    }
  }
  async sendLoop(role) {
    while (!this.controller.signal.aborted) { await this.sendOne(role); await this.pause(this.sendInterval); }
  }
  async run() {
    while (!this.controller.signal.aborted && !this.ready) {
      try { await this.initialize(); }
      catch (error) {
        if (!this.controller.signal.aborted) this.log.warn(error.message === 'telegram_existing_webhook' ? 'Telegram webhook already exists; remove it before starting this server.' : 'Telegram setup unavailable; check both bot settings and credentials.');
        await this.pause(10_000);
      }
    }
    if (this.controller.signal.aborted) return;
    await Promise.all(['child', 'guardian'].flatMap(role => [this.receiveLoop(role), this.sendLoop(role)]));
  }
  start() {
    if (!this.configured) return;
    if (!this.store.acquireLease(this.owner)) throw new Error('Another Telegram worker is already using this database.');
    this.leaseTimer = setInterval(() => {
      if (!this.store.acquireLease(this.owner)) { this.log.error('Telegram worker lease lost.'); this.controller.abort(); }
    }, 20_000);
    this.leaseTimer.unref();
    this.tasks.push(this.run());
  }
  async stop() {
    this.controller.abort();
    clearInterval(this.leaseTimer);
    await Promise.allSettled(this.tasks);
    this.store.releaseLease(this.owner);
    this.ready = false;
  }
}
