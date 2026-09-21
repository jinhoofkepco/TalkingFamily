import { createServer } from 'node:http';
import { ApiError, authenticate, validateEvent } from './protocol.js';

async function readJson(request) {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers['content-type'] || '')) throw new ApiError(415, 'json_required', 'Use application/json.');
  if (Number(request.headers['content-length']) > 16_384) { request.resume(); throw new ApiError(413, 'body_too_large', 'The request is too large.'); }
  const raw = await new Promise((resolve, reject) => {
    let size = 0;
    let oversized = false;
    const parts = [];
    request.on('data', part => {
      if (oversized) return;
      size += part.length;
      if (size > 16_384) { oversized = true; parts.length = 0; reject(new ApiError(413, 'body_too_large', 'The request is too large.')); }
      else parts.push(part);
    });
    request.on('end', () => { if (!oversized) resolve(Buffer.concat(parts).toString('utf8')); });
    request.on('error', () => reject(new ApiError(400, 'incomplete_body', 'The request body was incomplete.')));
    request.on('aborted', () => reject(new ApiError(400, 'incomplete_body', 'The request body was incomplete.')));
  });
  try { return JSON.parse(raw); }
  catch { throw new ApiError(400, 'invalid_json', 'Invalid JSON.'); }
}

export function createHttpServer({ store, relay, push, tokens, allowedOrigins = [], log = console }) {
  if (allowedOrigins.includes('*')) throw new Error('Wildcard CORS is not supported.');
  const buckets = new Map();
  const server = createServer(async (request, response) => {
    response.setHeader('content-type', 'application/json; charset=utf-8');
    response.setHeader('cache-control', 'no-store');
    response.setHeader('x-content-type-options', 'nosniff');
    const reply = (status, body) => { response.writeHead(status); response.end(JSON.stringify(body)); };
    try {
      const origin = request.headers.origin;
      if (origin) {
        if (!allowedOrigins.includes(origin)) throw new ApiError(403, 'origin_forbidden', 'This browser origin is not allowed.');
        response.setHeader('access-control-allow-origin', origin);
        response.setHeader('vary', 'Origin');
        response.setHeader('access-control-allow-headers', 'authorization,content-type');
        response.setHeader('access-control-allow-methods', 'GET,POST,OPTIONS');
      }
      if (request.method === 'OPTIONS') { response.writeHead(204); response.end(); return; }
      const path = new URL(request.url, 'http://localhost').pathname;
      if (request.method === 'GET' && path === '/health') return reply(200, { ok: true, transport: relay.configured ? 'telegram' : 'unconfigured', telegramReady: relay.ready, pushConfigured: Boolean(push) });
      const role = authenticate(request.headers.authorization, tokens);
      const now = Date.now();
      let bucket = buckets.get(role);
      if (!bucket || bucket.reset < now) { bucket = { count: 0, reset: now + 60_000 }; buckets.set(role, bucket); }
      if (++bucket.count > 180) { response.setHeader('retry-after', '60'); throw new ApiError(429, 'rate_limited', 'Please wait before trying again.'); }
      if (request.method === 'GET' && path === '/v1/state') return reply(200, { ...store.state(role), transport: relay.configured ? 'telegram' : 'unconfigured', pushConfigured: Boolean(push) });
      if (request.method === 'POST' && ['/v1/events', '/v1/push-token'].includes(path)) {
        if (!relay.configured) throw new ApiError(503, 'transport_unconfigured', 'Configure both Telegram bots before sending data.');
        const body = await readJson(request);
        if (path === '/v1/push-token') {
          if (typeof body?.token !== 'string' || body.token.length < 20 || body.token.length > 4096 || /\s/.test(body.token)) throw new ApiError(400, 'invalid_push_token', 'Invalid push registration token.');
          store.savePushToken(role, body.token);
          return reply(200, { registered: true, pushConfigured: Boolean(push) });
        }
        const result = store.insert(validateEvent(body, role));
        return reply(result.duplicate ? 200 : 202, { event: result.event });
      }
      throw new ApiError(404, 'not_found', 'Endpoint not found.');
    } catch (error) {
      if (response.headersSent) { response.end(); return; }
      if (!(error instanceof ApiError)) log.error('Request failed unexpectedly.');
      reply(error instanceof ApiError ? error.status : 500, { error: { code: error instanceof ApiError ? error.code : 'internal_error', message: error instanceof ApiError ? error.message : 'The request could not be completed.' } });
    }
  });
  server.requestTimeout = 15_000;
  server.headersTimeout = 10_000;
  server.maxHeadersCount = 40;
  server.keepAliveTimeout = 5000;
  return server;
}
