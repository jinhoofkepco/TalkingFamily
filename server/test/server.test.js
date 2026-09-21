import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdtempSync, rmSync, readFileSync, statSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../src/store.js';
import { validateEvent, envelope } from '../src/protocol.js';
import { TelegramRelay } from '../src/telegram.js';
import { createHttpServer } from '../src/http.js';
import { PushWorker } from '../src/push.js';

const tokens = { child: 'child-device-secret-'.repeat(3), guardian: 'guardian-device-secret-'.repeat(3) };
const silent = { warn() {}, error() {}, info() {} };
const event = (kind, payload, id = randomUUID()) => ({ id, kind, payload });
const chat = () => event('chat', { text: '아빠 이제 집에 가요' });
const normalized = (kind, payload, role = 'child') => validateEvent(event(kind, payload), role);
const deliver = (store, input, updateId) => {
  const recipient = input.sender === 'child' ? 'guardian' : 'child';
  const row = store.insert(input).event;
  store.receive(recipient, updateId ?? store.offset(recipient), envelope(row));
  return row;
};
const registerReward = (store, name, cost = 1, rewardId = randomUUID()) => {
  deliver(store, normalized('reward_upsert', { rewardId, name, cost }, 'guardian'));
  return { rewardId, reward: name, cost };
};

async function listen(server) { await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); return `http://127.0.0.1:${server.address().port}`; }
async function close(server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
async function fixture(t, configured = true) {
  const store = new Store();
  const relay = { configured, ready: configured };
  const server = createHttpServer({ store, relay, tokens, log: silent });
  const url = await listen(server);
  t.after(async () => { await close(server); store.close(); });
  const request = async (method, path, body, role = 'child', extra = {}) => {
    const result = await fetch(url + path, { method, headers: { authorization: `Bearer ${tokens[role]}`, 'content-type': 'application/json', ...extra }, body: body === undefined ? undefined : JSON.stringify(body) });
    return { status: result.status, body: await result.json() };
  };
  return { store, request, url };
}

test('HTTP requires bearer auth and rejects forbidden role actions and browser origins', async t => {
  const { request, url } = await fixture(t);
  assert.equal((await fetch(url + '/health')).status, 200);
  assert.equal((await fetch(url + '/v1/state')).status, 401);
  assert.equal((await request('POST', '/v1/events', event('sticker_award', { count: 1, reason: '잘했어' }))).status, 403);
  assert.equal((await request('POST', '/v1/events', event('sharing_status', { enabled: true }), 'guardian')).status, 403);
  assert.equal((await request('GET', '/v1/state', undefined, 'child', { origin: 'https://evil.example' })).status, 403);
  assert.throws(() => createHttpServer({ tokens, allowedOrigins: ['*'] }), /Wildcard/);
});

test('unconfigured transport refuses mutation and never locally delivers', async t => {
  const { request, store } = await fixture(t, false);
  const response = await request('POST', '/v1/events', chat());
  assert.equal(response.status, 503);
  assert.equal(response.body.error.code, 'transport_unconfigured');
  assert.equal(store.state('child').events.length, 0);
  assert.equal((await request('GET', '/v1/state')).body.transport, 'unconfigured');
});

test('pending event visible only to sender; exact UUID replay idempotent; conflict rejected', async t => {
  const { request, store } = await fixture(t);
  const input = chat();
  const first = await request('POST', '/v1/events', input);
  assert.equal(first.status, 202);
  assert.equal(first.body.event.delivery, 'pending');
  assert.equal((await request('POST', '/v1/events', input)).status, 200);
  assert.equal((await request('POST', '/v1/events', { ...input, payload: { text: 'changed' } })).status, 409);
  assert.equal((await request('POST', '/v1/events', input, 'guardian')).status, 409);
  assert.equal((await request('GET', '/v1/state')).body.events.length, 1);
  assert.equal((await request('GET', '/v1/state', undefined, 'guardian')).body.events.length, 0);
  store.receive('guardian', 5, envelope(first.body.event));
  assert.equal((await request('GET', '/v1/state', undefined, 'guardian')).body.events[0].delivery, 'relayed');
  assert.equal(store.offset('guardian'), 6);
});

test('validates sensor coordinates, timestamps, event size and four vertical phases', async t => {
  const { request } = await fixture(t);
  const location = { latitude: 37.5, longitude: 127, accuracy: 12, source: 'manual', capturedAt: new Date().toISOString() };
  assert.equal((await request('POST', '/v1/events', event('location', { ...location, latitude: 91 }))).status, 400);
  assert.equal((await request('POST', '/v1/events', event('location', { ...location, capturedAt: 'yesterday' }))).status, 400);
  assert.equal((await request('POST', '/v1/events', event('chat', { text: 'x'.repeat(1501) }))).status, 400);
  assert.equal((await request('POST', '/v1/events', event('chat', { text: 'x'.repeat(17_000) }))).status, 413);
  assert.equal((await request('POST', '/v1/events', event('location', location))).status, 202);
  const heartbeat = await request('POST', '/v1/events', event('heartbeat', { recordedAt: new Date().toISOString() }));
  assert.equal(heartbeat.status, 202);
  assert.equal('batteryPercent' in heartbeat.body.event.payload, false);
  for (const phase of ['ascent_started', 'ascent_finished', 'descent_started', 'descent_finished']) {
    assert.equal((await request('POST', '/v1/events', event('vertical', { phase, relativeMeters: 3, measuredAt: new Date().toISOString(), confidence: 'estimated' }))).status, 202);
  }
});

test('sticker ledger applies only once after relay; approvals reserve funds atomically', () => {
  const store = new Store();
  try {
    const award = store.insert(normalized('sticker_award', { count: 1, reason: '잘했어' }, 'guardian')).event;
    assert.equal(store.balance(), 0);
    store.receive('child', 1, envelope(award));
    store.receive('child', 2, envelope(award));
    assert.equal(store.balance(), 1);
    const first = deliver(store, normalized('sticker_redeem_request', registerReward(store, '아이스크림')), 1);
    const second = deliver(store, normalized('sticker_redeem_request', registerReward(store, '책')), 2);
    const approve = normalized('sticker_redeem_approve', { requestId: first.id, accepted: true }, 'guardian');
    const decision = store.insert(approve).event;
    assert.equal(store.balance(), 1);
    assert.equal(store.availableBalance(), 0);
    assert.throws(() => store.insert(normalized('sticker_redeem_approve', { requestId: first.id, accepted: false }, 'guardian')), e => e.code === 'already_decided');
    assert.throws(() => store.insert(normalized('sticker_redeem_approve', { requestId: second.id, accepted: true }, 'guardian')), e => e.code === 'insufficient_stickers');
    store.receive('child', store.offset('child'), envelope(decision));
    store.receive('child', store.offset('child'), envelope(decision));
    assert.equal(store.balance(), 0);
    assert.equal(store.state('child').redemptions.find(x => x.id === first.id).status, 'approved');
    assert.equal(store.insert(approve).duplicate, true);
    assert.equal(store.balance(), 0);
  } finally { store.close(); }
});

test('undelivered request cannot be approved; rejection does not charge', () => {
  const store = new Store();
  try {
    deliver(store, normalized('sticker_award', { count: 1, reason: '정리했어' }, 'guardian'), 1);
    const request = store.insert(normalized('sticker_redeem_request', registerReward(store, '보상'))).event;
    assert.throws(() => store.insert(normalized('sticker_redeem_approve', { requestId: request.id, accepted: true }, 'guardian')), e => e.code === 'request_not_delivered');
    store.receive('guardian', 1, envelope(request));
    deliver(store, normalized('sticker_redeem_approve', { requestId: request.id, accepted: false }, 'guardian'));
    assert.equal(store.balance(), 1);
    assert.equal(store.state('child').redemptions[0].status, 'rejected');
  } finally { store.close(); }
});

test('delayed GPS fix never replaces a fresher capturedAt position', () => {
  const store = new Store();
  try {
    const payload = { latitude: 37, longitude: 127, accuracy: 10, source: 'automatic' };
    const newest = deliver(store, normalized('location', { ...payload, capturedAt: '2026-01-02T00:00:00Z' }), 1);
    deliver(store, normalized('location', { ...payload, capturedAt: '2026-01-01T00:00:00Z' }), 2);
    assert.equal(store.state('guardian').latestLocation.id, newest.id);
  } finally { store.close(); }
});

async function fakeTelegram(t) {
  const identities = { CHILD: { id: 11, username: 'family_child_bot', is_bot: true }, GUARDIAN: { id: 22, username: 'family_guardian_bot', is_bot: true } };
  const updates = { CHILD: [], GUARDIAN: [] };
  const calls = [];
  let next = 10;
  let failSend = false;
  const server = createServer(async (req, res) => {
    const [, token, method] = /^\/bot([^/]+)\/(.+)$/.exec(req.url);
    let raw = ''; for await (const chunk of req) raw += chunk;
    const body = JSON.parse(raw);
    calls.push({ token, method, body });
    res.setHeader('content-type', 'application/json');
    const finish = result => res.end(JSON.stringify({ ok: true, result }));
    if (method === 'getMe') return finish(identities[token]);
    if (method === 'getWebhookInfo') return finish({ url: '' });
    if (method === 'getUpdates') return finish(updates[token].filter(u => u.update_id >= body.offset));
    if (method === 'sendMessage') {
      if (failSend) { res.statusCode = 503; return res.end(JSON.stringify({ ok: false, error_code: 503 })); }
      const peer = token === 'CHILD' ? 'GUARDIAN' : 'CHILD';
      assert.equal(body.chat_id, `@${identities[peer].username}`);
      updates[peer].push({ update_id: next++, message: { from: identities[token], chat: { id: identities[token].id, type: 'private' }, text: body.text } });
      return finish({ message_id: 1 });
    }
    res.statusCode = 404; res.end('{}');
  });
  const apiBase = await listen(server);
  t.after(() => close(server));
  return { apiBase, calls, updates, identities, setFailSend: value => { failSend = value; } };
}

test('actual HTTP Telegram route required: sender response alone cannot deliver, receiver validates peer', async t => {
  const fake = await fakeTelegram(t);
  const store = new Store();
  const relay = new TelegramRelay(store, { tokens: { child: 'CHILD', guardian: 'GUARDIAN' }, apiBase: fake.apiBase, pollTimeout: 0, log: silent });
  t.after(async () => { await relay.stop(); store.close(); });
  await relay.initialize();
  const row = store.insert(validateEvent(chat(), 'child')).event;
  await relay.sendOne('child');
  assert.equal(store.get(row.id).delivery, 'pending');
  assert.equal(store.state('guardian').events.length, 0);
  const genuine = fake.updates.GUARDIAN.pop();
  fake.updates.GUARDIAN.push({ ...genuine, message: { ...genuine.message, from: { id: 999, is_bot: true } } });
  await relay.pollOnce('guardian');
  assert.equal(store.get(row.id).delivery, 'pending');
  fake.updates.GUARDIAN.push({ ...genuine, update_id: 11, message: { ...genuine.message, text: genuine.message.text.replace('아빠', '가짜') } });
  await relay.pollOnce('guardian');
  assert.equal(store.get(row.id).delivery, 'pending');
  fake.updates.GUARDIAN.push({ ...genuine, update_id: 12 });
  await relay.pollOnce('guardian');
  assert.equal(store.get(row.id).delivery, 'relayed');
  assert.equal(store.offset('guardian'), 13);
  assert.equal(JSON.parse(fake.calls.find(c => c.method === 'sendMessage').body.text).event.payload.text, '아빠 이제 집에 가요');
});

test('pending queue, ledger, offsets and worker lease survive process restart', async t => {
  const fake = await fakeTelegram(t);
  const dir = mkdtempSync(join(tmpdir(), 'family-server-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const path = join(dir, 'family.sqlite');
  let store = new Store(path);
  const row = store.insert(normalized('sticker_award', { count: 1, reason: '도착했어' }, 'guardian')).event;
  assert.equal(store.acquireLease('first', 100), true);
  assert.equal(store.acquireLease('second', 101), false);
  assert.equal(store.acquireLease('second', 90_101), true);
  store.receive('child', 5, 'not an event');
  store.close();
  store = new Store(path);
  const relay = new TelegramRelay(store, { tokens: { child: 'CHILD', guardian: 'GUARDIAN' }, apiBase: fake.apiBase, pollTimeout: 0, log: silent });
  await relay.initialize();
  assert.equal(store.offset('child'), 6);
  await relay.sendOne('guardian');
  await relay.pollOnce('child');
  assert.equal(store.get(row.id).delivery, 'relayed');
  assert.equal(store.balance(), 1);
  await relay.stop(); store.close();
  store = new Store(path);
  assert.equal(store.balance(), 1);
  assert.equal(store.offset('child'), 11);
  store.receive('child', 11, envelope(row));
  assert.equal(store.balance(), 1);
  store.close();
});

test('Telegram outages keep pending records and back off instead of local fallback', async t => {
  const fake = await fakeTelegram(t);
  const store = new Store();
  const relay = new TelegramRelay(store, { tokens: { child: 'CHILD', guardian: 'GUARDIAN' }, apiBase: fake.apiBase, pollTimeout: 0, log: silent });
  t.after(async () => { await relay.stop(); store.close(); });
  await relay.initialize();
  const row = store.insert(validateEvent(chat(), 'child')).event;
  fake.setFailSend(true);
  await relay.sendOne('child');
  assert.equal(store.get(row.id).delivery, 'pending');
  assert.equal(store.attempts(row.id), 1);
  assert.equal(await relay.sendOne('child'), false);
  assert.equal(store.state('guardian').events.length, 0);
});

test('push is queued only after relay and contains only event reference', async () => {
  const store = new Store();
  const calls = [];
  const worker = new PushWorker(store, { send: async (...args) => { calls.push(args); } }, silent);
  try {
    store.savePushToken('guardian', 'firebase-token-placeholder');
    const row = store.insert(validateEvent(chat(), 'child')).event;
    await worker.tick(); assert.equal(calls.length, 0);
    store.receive('guardian', 1, envelope(row));
    await worker.tick(); await worker.tick();
    assert.deepEqual(calls, [['firebase-token-placeholder', row.id, 'chat']]);
  } finally { await worker.stop(); store.close(); }
});

test('approval reservation survives restart and prevents overlapping delayed decisions', () => {
  const dir = mkdtempSync(join(tmpdir(), 'family-reservation-test-'));
  const path = join(dir, 'family.sqlite');
  let store = new Store(path);
  try {
    deliver(store, normalized('sticker_award', { count: 1, reason: '잘했어' }, 'guardian'), 1);
    const first = deliver(store, normalized('sticker_redeem_request', registerReward(store, '책')), 1);
    const second = deliver(store, normalized('sticker_redeem_request', registerReward(store, '간식')), 2);
    const decision = store.insert(normalized('sticker_redeem_approve', { requestId: first.id, accepted: true }, 'guardian')).event;
    store.close(); store = new Store(path);
    assert.equal(store.availableBalance(), 0);
    assert.throws(() => store.insert(normalized('sticker_redeem_approve', { requestId: second.id, accepted: true }, 'guardian')), e => e.code === 'insufficient_stickers');
    assert.throws(() => store.insert(normalized('sticker_redeem_approve', { requestId: first.id, accepted: false }, 'guardian')), e => e.code === 'already_decided');
    store.receive('child', store.offset('child'), envelope(decision));
    assert.equal(store.balance(), 0);
    assert.equal(store.state('child').redemptions.find(r => r.id === first.id).status, 'approved');
  } finally { store.close(); rmSync(dir, { recursive: true, force: true }); }
});

test('setup creates private distinct device tokens without logging or overwriting them', () => {
  const dir = mkdtempSync(join(tmpdir(), 'family-setup-test-'));
  try {
    const script = fileURLToPath(new URL('../src/setup.js', import.meta.url));
    const first = spawnSync(process.execPath, [script], { cwd: dir, encoding: 'utf8' });
    assert.equal(first.status, 0);
    const contents = readFileSync(join(dir, '.env'), 'utf8');
    const child = /^CHILD_DEVICE_TOKEN=([a-f0-9]{64})$/m.exec(contents)[1];
    const guardian = /^GUARDIAN_DEVICE_TOKEN=([a-f0-9]{64})$/m.exec(contents)[1];
    assert.notEqual(child, guardian);
    assert.equal((first.stdout + first.stderr).includes(child), false);
    assert.equal((first.stdout + first.stderr).includes(guardian), false);
    if (process.platform !== 'win32') assert.equal(statSync(join(dir, '.env')).mode & 0o777, 0o600);
    const second = spawnSync(process.execPath, [script], { cwd: dir, encoding: 'utf8' });
    assert.equal(second.status, 1);
    assert.equal(readFileSync(join(dir, '.env'), 'utf8'), contents);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('reward catalog mutations require guardian role and validate names, IDs and costs', async t => {
  const { request } = await fixture(t);
  const rewardId = randomUUID();
  for (const kind of ['reward_upsert', 'reward_delete']) {
    assert.equal((await request('POST', '/v1/events', event(kind, { rewardId, name: '간식', cost: 1 }))).status, 403);
  }
  for (const payload of [
    { rewardId: 'bad', name: '간식', cost: 1 },
    { rewardId, name: '  ', cost: 1 },
    { rewardId, name: '가'.repeat(61), cost: 1 },
    { rewardId, name: '간식', cost: 0 },
    { rewardId, name: '간식', cost: 1.5 },
    { rewardId, name: '간식', cost: 1000 },
    { rewardId, name: '간식', cost: '5' },
  ]) assert.equal((await request('POST', '/v1/events', event('reward_upsert', payload), 'guardian')).status, 400);
  assert.equal((await request('POST', '/v1/events', event('reward_delete', { rewardId: 'bad' }), 'guardian')).status, 400);
  const accepted = await request('POST', '/v1/events', event('reward_upsert', { rewardId, name: '  ' + '가'.repeat(60) + '  ', cost: 999 }), 'guardian');
  assert.equal(accepted.status, 202);
  assert.equal(accepted.body.event.payload.name, '가'.repeat(60));
});

test('reward create/edit/delete relay in guardian order, persist across restart and replay once', () => {
  const dir = mkdtempSync(join(tmpdir(), 'family-rewards-test-'));
  const path = join(dir, 'family.sqlite');
  let store = new Store(path);
  const rewardId = randomUUID();
  const create = normalized('reward_upsert', { rewardId, name: '아이스크림', cost: 5 }, 'guardian');
  const edit = normalized('reward_upsert', { rewardId, name: '새 약속', cost: 3 }, 'guardian');
  const remove = normalized('reward_delete', { rewardId }, 'guardian');
  try {
    const created = store.insert(create).event;
    assert.equal(store.insert(create).duplicate, true);
    assert.deepEqual(store.state('guardian').rewards, []);
    assert.deepEqual(store.state('child').rewards, []);
    assert.equal(store.state('guardian').events[0].delivery, 'pending');
    assert.throws(() => store.insert({ ...create, payload: { ...create.payload, cost: 7 } }), e => e.code === 'id_conflict');
    store.insert(edit);
    store.insert(remove);
    assert.equal(store.pending('guardian').id, create.id);
    store.close(); store = new Store(path);
    store.receive('child', store.offset('child'), envelope(created));
    assert.deepEqual(store.state('child').rewards.map(r => ({ ...r })), [{ id: rewardId, name: '아이스크림', cost: 5 }]);
    assert.equal(store.pending('guardian').id, edit.id);
    store.close(); store = new Store(path);
    assert.equal(store.state('guardian').rewards[0].name, '아이스크림');
    const edited = store.get(edit.id);
    store.receive('child', store.offset('child'), envelope(edited));
    assert.deepEqual(store.state('child').rewards.map(r => ({ ...r })), [{ id: rewardId, name: '새 약속', cost: 3 }]);
    // A late duplicate create must not overwrite the later edit.
    store.receive('child', store.offset('child'), envelope(created));
    assert.equal(store.state('child').rewards[0].cost, 3);
    assert.equal(store.pending('guardian').id, remove.id);
    store.receive('child', store.offset('child'), envelope(store.get(remove.id)));
    assert.deepEqual(store.state('child').rewards, []);
    assert.equal(store.insert(remove).duplicate, true);
    store.close(); store = new Store(path);
    assert.deepEqual(store.state('guardian').rewards, []);
    assert.equal(store.pending('guardian'), null);
    // Offline delete is safe even if the referenced item is already absent.
    deliver(store, normalized('reward_delete', { rewardId }, 'guardian'));
    assert.deepEqual(store.state('child').rewards, []);
  } finally { store.close(); rmSync(dir, { recursive: true, force: true }); }
});

test('redemption rejects forged/stale catalog data while accepted snapshots survive edits and deletion', () => {
  const store = new Store();
  try {
    for (let i = 0; i < 5; i++) deliver(store, normalized('sticker_award', { count: 1, reason: '잘했어' }, 'guardian'));
    const reward = registerReward(store, '아이스크림', 3);
    const request = normalized('sticker_redeem_request', reward);
    assert.throws(() => store.insert(normalized('sticker_redeem_request', { ...reward, rewardId: randomUUID() })), e => e.code === 'reward_unavailable');
    assert.throws(() => store.insert(normalized('sticker_redeem_request', { ...reward, cost: 1 })), e => e.code === 'reward_changed');
    assert.throws(() => store.insert(normalized('sticker_redeem_request', { ...reward, reward: '비싼 선물' })), e => e.code === 'reward_changed');
    const edit = store.insert(normalized('reward_upsert', { rewardId: reward.rewardId, name: '책', cost: 4 }, 'guardian')).event;
    // Pending edits do not change what the child can currently request.
    const accepted = store.insert(request).event;
    store.receive('child', store.offset('child'), envelope(edit));
    assert.throws(() => store.insert(normalized('sticker_redeem_request', reward)), e => e.code === 'reward_changed');
    assert.equal(store.insert(request).duplicate, true);
    deliver(store, normalized('reward_delete', { rewardId: reward.rewardId }, 'guardian'));
    assert.throws(() => store.insert(normalized('sticker_redeem_request', { ...reward, reward: '책', cost: 4 })), e => e.code === 'reward_unavailable');
    store.receive('guardian', store.offset('guardian'), envelope(accepted));
    assert.deepEqual(store.state('guardian').redemptions.map(r => ({ ...r })), [{ id: request.id, reward: '아이스크림', cost: 3, status: 'pending' }]);
    assert.equal(store.insert(request).duplicate, true);
    const approval = normalized('sticker_redeem_approve', { requestId: request.id, accepted: true }, 'guardian');
    deliver(store, approval);
    assert.equal(store.balance(), 2);
    assert.equal(store.state('guardian').redemptions[0].status, 'approved');
    assert.equal(store.insert(approval).duplicate, true);
    assert.equal(store.balance(), 2);
  } finally { store.close(); }
});

test('a reward cannot be requested before its first relay and HTTP state exposes only delivered catalog', async t => {
  const { request, store } = await fixture(t);
  deliver(store, normalized('sticker_award', { count: 1, reason: '잘했어' }, 'guardian'));
  const rewardId = randomUUID();
  const create = await request('POST', '/v1/events', event('reward_upsert', { rewardId, name: '산책', cost: 1 }), 'guardian');
  const redeem = event('sticker_redeem_request', { rewardId, reward: '산책', cost: 1 });
  const unavailable = await request('POST', '/v1/events', redeem);
  assert.equal(unavailable.status, 409);
  assert.equal(unavailable.body.error.code, 'reward_unavailable');
  assert.deepEqual((await request('GET', '/v1/state')).body.rewards, []);
  store.receive('child', store.offset('child'), envelope(create.body.event));
  assert.deepEqual((await request('GET', '/v1/state')).body.rewards, [{ id: rewardId, name: '산책', cost: 1 }]);
  assert.equal((await request('POST', '/v1/events', redeem)).status, 202);
  assert.equal((await request('POST', '/v1/events', redeem)).status, 200);
  assert.equal((await request('POST', '/v1/events', event('sticker_redeem_request', { reward: '자유 입력', cost: 1 }))).status, 400);
});
