import { DatabaseSync } from 'node:sqlite';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { ApiError, envelope } from './protocol.js';

const conflict = (code, message) => { throw new ApiError(409, code, message); };
const toEvent = (r) => r ? ({ id: r.id, kind: r.kind, payload: JSON.parse(r.payload), sender: r.sender, createdAt: r.created_at, delivery: r.delivery }) : null;

export class Store {
  constructor(path = ':memory:') {
    if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
    this.db = new DatabaseSync(path);
    this.db.exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;
      CREATE TABLE IF NOT EXISTS events (
        seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, payload TEXT NOT NULL,
        sender TEXT NOT NULL, created_at TEXT NOT NULL, delivery TEXT NOT NULL DEFAULT 'pending',
        attempts INTEGER NOT NULL DEFAULT 0, retry_at INTEGER NOT NULL DEFAULT 0
      );
      CREATE INDEX IF NOT EXISTS events_pending ON events(delivery,sender,seq);
      CREATE TABLE IF NOT EXISTS ledger (event_id TEXT PRIMARY KEY REFERENCES events(id), amount INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS rewards (id TEXT PRIMARY KEY, name TEXT NOT NULL, cost INTEGER NOT NULL CHECK(cost BETWEEN 1 AND 999));
      CREATE TABLE IF NOT EXISTS redemptions (id TEXT PRIMARY KEY REFERENCES events(id), reward TEXT NOT NULL, cost INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'pending', decision_id TEXT);
      CREATE TABLE IF NOT EXISTS approval_reservations (event_id TEXT PRIMARY KEY REFERENCES events(id), request_id TEXT NOT NULL UNIQUE REFERENCES redemptions(id), cost INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS offsets (role TEXT PRIMARY KEY, next_id INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS push_tokens (role TEXT PRIMARY KEY, token TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS push_jobs (event_id TEXT PRIMARY KEY REFERENCES events(id), role TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, retry_at INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS worker_lease (name TEXT PRIMARY KEY, owner TEXT NOT NULL, expires_at INTEGER NOT NULL);
    `);
  }
  close() { this.db.close(); }
  transaction(work) {
    this.db.exec('BEGIN IMMEDIATE');
    try { const result = work(); this.db.exec('COMMIT'); return result; }
    catch (error) { this.db.exec('ROLLBACK'); throw error; }
  }
  get(id) { return toEvent(this.db.prepare('SELECT * FROM events WHERE id=?').get(id)); }
  balance() { return this.db.prepare('SELECT COALESCE(SUM(amount),0) AS value FROM ledger').get().value; }
  availableBalance() { return this.balance() - this.db.prepare('SELECT COALESCE(SUM(cost),0) AS value FROM approval_reservations').get().value; }
  insert(input) {
    return this.transaction(() => {
      const existing = this.get(input.id);
      if (existing) {
        if (existing.sender !== input.sender || existing.kind !== input.kind || JSON.stringify(existing.payload) !== JSON.stringify(input.payload)) conflict('id_conflict', 'This event ID was already used for different data.');
        return { event: existing, duplicate: true };
      }
      const p = input.payload;
      if (input.kind === 'sticker_redeem_request') {
        // The relayed catalog is authoritative. Pending guardian changes become effective
        // in sender order, so a queued edit cannot silently change an accepted request.
        const reward = this.db.prepare('SELECT name,cost FROM rewards WHERE id=?').get(p.rewardId);
        if (!reward) conflict('reward_unavailable', 'This reward is no longer available. Refresh the reward list.');
        if (reward.name !== p.reward || reward.cost !== p.cost) conflict('reward_changed', 'This reward has changed. Refresh the reward list before requesting it.');
        if (p.cost > this.availableBalance()) conflict('insufficient_stickers', 'There are not enough available stickers.');
      }
      if (input.kind === 'sticker_redeem_approve') {
        const request = this.db.prepare('SELECT * FROM redemptions WHERE id=?').get(p.requestId);
        if (!request) conflict('request_not_delivered', 'This redemption request has not been delivered.');
        if (request.status !== 'pending' || this.db.prepare('SELECT 1 FROM approval_reservations WHERE request_id=?').get(p.requestId)) conflict('already_decided', 'A decision for this redemption already exists.');
        if (p.accepted && request.cost > this.availableBalance()) conflict('insufficient_stickers', 'There are not enough available stickers.');
      }
      const event = { ...input, createdAt: new Date().toISOString(), delivery: 'pending' };
      this.db.prepare('INSERT INTO events(id,kind,payload,sender,created_at) VALUES(?,?,?,?,?)').run(event.id, event.kind, JSON.stringify(event.payload), event.sender, event.createdAt);
      if (event.kind === 'sticker_redeem_approve') {
        const request = this.db.prepare('SELECT cost FROM redemptions WHERE id=?').get(p.requestId);
        this.db.prepare('INSERT INTO approval_reservations(event_id,request_id,cost) VALUES(?,?,?)').run(event.id, p.requestId, p.accepted ? request.cost : 0);
      }
      return { event, duplicate: false };
    });
  }
  pending(role, now = Date.now()) {
    // Keep each direction in order, even while its oldest message is retrying.
    const row = this.db.prepare("SELECT * FROM events WHERE sender=? AND delivery='pending' ORDER BY seq LIMIT 1").get(role);
    return row && row.retry_at <= now ? toEvent(row) : null;
  }
  attempt(id, retryMs) {
    this.db.prepare("UPDATE events SET attempts=attempts+1,retry_at=? WHERE id=? AND delivery='pending'").run(Date.now() + retryMs, id);
  }
  attempts(id) { return this.db.prepare('SELECT attempts FROM events WHERE id=?').get(id)?.attempts ?? 0; }
  offset(role) { return this.db.prepare('SELECT next_id FROM offsets WHERE role=?').get(role)?.next_id ?? 0; }
  receive(role, updateId, wireText) {
    return this.transaction(() => {
      if (updateId < this.offset(role)) return null;
      this.db.prepare('INSERT INTO offsets(role,next_id) VALUES(?,?) ON CONFLICT(role) DO UPDATE SET next_id=MAX(next_id,excluded.next_id)').run(role, updateId + 1);
      let message;
      try { message = JSON.parse(wireText); } catch { return null; }
      if (message?.v !== 1 || !message.event || typeof message.event.id !== 'string') return null;
      const event = this.get(message.event.id);
      if (!event || event.sender === role || event.delivery === 'relayed' || envelope(event) !== wireText) return null;
      const p = event.payload;
      switch (event.kind) {
        case 'reward_upsert':
          this.db.prepare('INSERT INTO rewards(id,name,cost) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,cost=excluded.cost').run(p.rewardId, p.name, p.cost); break;
        case 'reward_delete':
          // Deleting an absent item is safe, including create/delete queued while offline.
          // Redemptions retain their accepted name and cost independently of the catalog.
          this.db.prepare('DELETE FROM rewards WHERE id=?').run(p.rewardId); break;
        case 'sticker_award':
          this.db.prepare('INSERT INTO ledger(event_id,amount) VALUES(?,1)').run(event.id); break;
        case 'sticker_redeem_request':
          this.db.prepare('INSERT INTO redemptions(id,reward,cost) VALUES(?,?,?)').run(event.id, p.reward, p.cost); break;
        case 'sticker_redeem_approve': {
          const request = this.db.prepare('SELECT * FROM redemptions WHERE id=?').get(p.requestId);
          const reservation = this.db.prepare('SELECT * FROM approval_reservations WHERE event_id=?').get(event.id);
          if (!request || request.status !== 'pending' || !reservation) throw new Error('Invalid redemption reservation invariant.');
          if (p.accepted) {
            if (this.balance() < request.cost) throw new Error('Sticker balance invariant violated.');
            this.db.prepare('INSERT INTO ledger(event_id,amount) VALUES(?,?)').run(event.id, -request.cost);
          }
          this.db.prepare('UPDATE redemptions SET status=?,decision_id=? WHERE id=?').run(p.accepted ? 'approved' : 'rejected', event.id, request.id);
          this.db.prepare('DELETE FROM approval_reservations WHERE event_id=?').run(event.id);
          break;
        }
      }
      this.db.prepare("UPDATE events SET delivery='relayed' WHERE id=?").run(event.id);
      // Automatic sensor updates do not buzz the parent's phone every five minutes.
      if (!['heartbeat', 'vertical', 'sharing_status'].includes(event.kind) && !(event.kind === 'location' && p.source === 'automatic')) this.db.prepare('INSERT OR IGNORE INTO push_jobs(event_id,role) VALUES(?,?)').run(event.id, role);
      return { ...event, delivery: 'relayed' };
    });
  }
  state(role) {
    const visible = "(delivery='relayed' OR sender=?)";
    const events = this.db.prepare(`SELECT * FROM (SELECT * FROM events WHERE ${visible} ORDER BY seq DESC LIMIT 1000) ORDER BY seq`).all(role).map(toEvent);
    const latest = (kind, timeField) => toEvent(this.db.prepare(`SELECT * FROM events WHERE kind=? AND ${visible} ORDER BY json_extract(payload,'$.${timeField}') DESC,seq DESC LIMIT 1`).get(kind, role));
    const sharing = toEvent(this.db.prepare(`SELECT * FROM events WHERE kind='sharing_status' AND ${visible} ORDER BY seq DESC LIMIT 1`).get(role));
    return {
      role, events, stickerBalance: this.balance(),
      rewards: this.db.prepare('SELECT id,name,cost FROM rewards ORDER BY rowid').all(),
      redemptions: this.db.prepare('SELECT id,reward,cost,status FROM redemptions ORDER BY rowid').all(),
      sharingEnabled: sharing?.payload.enabled ?? false,
      latestLocation: latest('location', 'capturedAt'), latestHeartbeat: latest('heartbeat', 'recordedAt'),
    };
  }
  savePushToken(role, token) { this.db.prepare('INSERT INTO push_tokens(role,token) VALUES(?,?) ON CONFLICT(role) DO UPDATE SET token=excluded.token').run(role, token); }
  pushJob() {
    return this.db.prepare('SELECT j.*,t.token FROM push_jobs j JOIN push_tokens t ON t.role=j.role WHERE j.retry_at<=? ORDER BY j.rowid LIMIT 1').get(Date.now());
  }
  pushDone(id) { this.db.prepare('DELETE FROM push_jobs WHERE event_id=?').run(id); }
  pushFailed(id) { this.db.prepare('UPDATE push_jobs SET attempts=attempts+1,retry_at=? WHERE event_id=?').run(Date.now() + 60_000, id); }
  removePushToken(role, token) { this.db.prepare('DELETE FROM push_tokens WHERE role=? AND token=?').run(role, token); }
  acquireLease(owner, now = Date.now()) {
    return this.transaction(() => {
      const active = this.db.prepare("SELECT * FROM worker_lease WHERE name='telegram'").get();
      if (active && active.owner !== owner && active.expires_at > now) return false;
      this.db.prepare("INSERT INTO worker_lease(name,owner,expires_at) VALUES('telegram',?,?) ON CONFLICT(name) DO UPDATE SET owner=excluded.owner,expires_at=excluded.expires_at").run(owner, now + 90_000);
      return true;
    });
  }
  releaseLease(owner) { this.db.prepare('DELETE FROM worker_lease WHERE owner=?').run(owner); }
}
