import { createHash, timingSafeEqual } from 'node:crypto';

export class ApiError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}
const fail = (message) => { throw new ApiError(400, 'invalid_event', message); };
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const str = (value, name, max) => {
  if (typeof value !== 'string' || !value.trim() || value.length > max) fail(`${name} must contain 1–${max} characters.`);
  return value.trim();
};
const rewardName = (value, name) => str(typeof value === 'string' ? value.trim() : value, name, 60);
const num = (value, name, min, max) => {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max) fail(`${name} is outside its allowed range.`);
  return value;
};
const bool = (v, name) => { if (typeof v !== 'boolean') fail(`${name} must be boolean.`); return v; };
const timestamp = (v, name) => {
  if (typeof v !== 'string' || v.length > 40 || !/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(v) || !Number.isFinite(Date.parse(v))) fail(`${name} must be an ISO timestamp with timezone.`);
  if (Date.parse(v) > Date.now() + 5 * 60_000) fail(`${name} is too far in the future.`);
  return new Date(v).toISOString();
};
const id = (v) => { if (typeof v !== 'string' || !uuid.test(v)) fail('id must be a UUID.'); return v.toLowerCase(); };
const allowed = {
  child: new Set(['chat', 'location', 'vertical', 'sticker_redeem_request', 'sharing_status', 'heartbeat']),
  guardian: new Set(['chat', 'sticker_award', 'sticker_redeem_approve', 'reward_upsert', 'reward_delete']),
};

export function validateEvent(body, role) {
  if (!isObject(body) || !isObject(body.payload)) fail('An event object with a payload is required.');
  if (!allowed[role]?.has(body.kind)) throw new ApiError(403, 'forbidden_event', 'This device cannot send this event type.');
  const p = body.payload;
  let payload;
  switch (body.kind) {
    case 'chat': payload = { text: str(p.text, 'text', 1500) }; break;
    case 'location':
      if (!['manual', 'automatic'].includes(p.source)) fail('Unknown location source.');
      payload = { latitude: num(p.latitude, 'latitude', -90, 90), longitude: num(p.longitude, 'longitude', -180, 180), accuracy: num(p.accuracy, 'accuracy', 0, 100_000), capturedAt: timestamp(p.capturedAt, 'capturedAt'), source: p.source }; break;
    case 'vertical':
      if (!['ascent_started', 'ascent_finished', 'descent_started', 'descent_finished'].includes(p.phase) || p.confidence !== 'estimated') fail('Unknown vertical phase or confidence.');
      payload = { phase: p.phase, relativeMeters: num(p.relativeMeters, 'relativeMeters', -10_000, 10_000), measuredAt: timestamp(p.measuredAt, 'measuredAt'), confidence: 'estimated' };
      if (p.latitude !== undefined || p.longitude !== undefined) {
        payload.latitude = num(p.latitude, 'latitude', -90, 90);
        payload.longitude = num(p.longitude, 'longitude', -180, 180);
      }
      break;
    case 'sticker_award':
      if (p.count !== 1) fail('Award one sticker at a time.');
      payload = { count: 1, reason: str(p.reason, 'reason', 200) }; break;
    case 'sticker_redeem_request':
      if (!Number.isInteger(p.cost) || p.cost < 1 || p.cost > 999) fail('cost must be an integer from 1 to 999.');
      payload = { rewardId: id(p.rewardId), cost: p.cost, reward: rewardName(p.reward, 'reward') }; break;
    case 'sticker_redeem_approve': payload = { requestId: id(p.requestId), accepted: bool(p.accepted, 'accepted') }; break;
    case 'reward_upsert':
      if (!Number.isInteger(p.cost) || p.cost < 1 || p.cost > 999) fail('cost must be an integer from 1 to 999.');
      payload = { rewardId: id(p.rewardId), name: rewardName(p.name, 'name'), cost: p.cost }; break;
    case 'reward_delete': payload = { rewardId: id(p.rewardId) }; break;
    case 'sharing_status': payload = { enabled: bool(p.enabled, 'enabled') }; break;
    case 'heartbeat':
      payload = { recordedAt: timestamp(p.recordedAt, 'recordedAt') };
      if (p.batteryPercent !== undefined) payload.batteryPercent = num(p.batteryPercent, 'batteryPercent', 0, 100);
      break;
  }
  const result = { id: id(body.id), kind: body.kind, payload, sender: role };
  // Telegram text limit is 4096 UTF-16 code units; leave space for the envelope.
  if (JSON.stringify(result).length > 3700) fail('Encoded event exceeds the transport limit.');
  return result;
}

export function authenticate(header, tokens) {
  if (typeof header !== 'string' || !header.startsWith('Bearer ') || header.length > 512) throw new ApiError(401, 'unauthorized', 'A valid device token is required.');
  const actual = createHash('sha256').update(header.slice(7)).digest();
  let role = null;
  for (const [candidate, token] of Object.entries(tokens)) {
    const expected = createHash('sha256').update(token || '').digest();
    if (timingSafeEqual(actual, expected) && token) role = candidate;
  }
  if (!role) throw new ApiError(401, 'unauthorized', 'A valid device token is required.');
  return role;
}

export const peerOf = (role) => role === 'child' ? 'guardian' : 'child';
export const envelope = (event) => JSON.stringify({ v: 1, event: { id: event.id, kind: event.kind, payload: event.payload, sender: event.sender, createdAt: event.createdAt } });
