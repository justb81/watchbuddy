import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import request from 'supertest';
import { createApp } from '../app.js';

function mockFetch(status, body) {
  const jsonStr = JSON.stringify(body);
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    text: () => Promise.resolve(jsonStr),
  });
}

function mockFetchHtml(status) {
  return vi.fn().mockResolvedValue({
    ok: false,
    status,
    text: () => Promise.resolve('<html><body>Error</body></html>'),
  });
}

function buildApp(fetchFn, overrides = {}) {
  return createApp({
    clientId: 'test-client-id',
    clientSecret: 'test-client-secret',
    traktApi: 'https://api.trakt.tv',
    fetchFn,
    ...overrides,
  });
}

// ── Trust proxy ───────────────────────────────────────────────────────────────

describe('Trust proxy configuration', () => {
  it('has trust proxy enabled', () => {
    const app = buildApp(mockFetch(200, {}));
    expect(app.get('trust proxy')).toBe(1);
  });

  it('rate limiter does not throw with X-Forwarded-For header present', async () => {
    const app = buildApp(mockFetch(200, {
      access_token: 'acc', refresh_token: 'ref', expires_in: 1, token_type: 'Bearer', scope: 'public',
    }));
    const res = await request(app)
      .post('/trakt/token')
      .set('X-Forwarded-For', '1.2.3.4')
      .send({ code: 'device-code' });
    expect(res.status).not.toBe(500);
  });
});

// ── Health endpoint ───────────────────────────────────────────────────────────

describe('GET /health', () => {
  it('returns 200 ok', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app).get('/health');
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ status: 'ok' });
  });
});

// ── Token exchange endpoint ───────────────────────────────────────────────────

describe('POST /trakt/token', () => {
  let fetchFn;
  let app;

  beforeEach(() => {
    fetchFn = mockFetch(200, {
      access_token: 'acc-123',
      refresh_token: 'ref-456',
      expires_in: 7776000,
      token_type: 'Bearer',
      scope: 'public',
    });
    app = buildApp(fetchFn);
  });

  it('returns 400 when code is missing', async () => {
    const res = await request(app).post('/trakt/token').send({});
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('Missing code');
  });

  it('exchanges code for tokens successfully', async () => {
    const res = await request(app).post('/trakt/token').send({ code: 'device-code-abc' });
    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      access_token: 'acc-123',
      refresh_token: 'ref-456',
      expires_in: 7776000,
      token_type: 'Bearer',
      scope: 'public',
    });
  });

  it('does not leak client_secret in response', async () => {
    const res = await request(app).post('/trakt/token').send({ code: 'device-code-abc' });
    expect(res.body).not.toHaveProperty('client_secret');
    expect(JSON.stringify(res.body)).not.toContain('test-client-secret');
  });

  it('forwards upstream 400 from Trakt', async () => {
    const res = await request(buildApp(mockFetch(400, { error: 'invalid_grant' })))
      .post('/trakt/token')
      .send({ code: 'bad-code' });
    expect(res.status).toBe(400);
    expect(res.body).toEqual({ error: 'invalid_grant' });
  });

  it('forwards upstream 429 from Trakt', async () => {
    const res = await request(buildApp(mockFetch(429, { error: 'slow_down' })))
      .post('/trakt/token')
      .send({ code: 'code' });
    expect(res.status).toBe(429);
  });

  it('returns 502 on network error', async () => {
    const failFetch = vi.fn().mockRejectedValue(new Error('ECONNREFUSED'));
    const res = await request(buildApp(failFetch)).post('/trakt/token').send({ code: 'code' });
    expect(res.status).toBe(502);
    expect(res.body).toEqual({ error: 'Upstream error' });
  });

  it('returns 502 when Trakt responds with non-JSON on a 2xx status', async () => {
    const res = await request(buildApp(mockFetchHtml(200)))
      .post('/trakt/token')
      .send({ code: 'code' });
    expect(res.status).toBe(502);
    expect(res.body.error).toMatch(/non-JSON/i);
    expect(res.body.error).toContain('200');
  });

  it('returns 502 when Trakt responds with non-JSON on an error status', async () => {
    const res = await request(buildApp(mockFetchHtml(503)))
      .post('/trakt/token')
      .send({ code: 'code' });
    expect(res.status).toBe(502);
    expect(res.body.error).toMatch(/non-JSON/i);
    expect(res.body.error).toContain('503');
  });
});

// ── Error logging ─────────────────────────────────────────────────────────────

describe('Error logging', () => {
  let errorSpy;
  let warnSpy;

  beforeEach(() => {
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    errorSpy.mockRestore();
    warnSpy.mockRestore();
  });

  it('logs body snippet when Trakt returns 403 on token exchange', async () => {
    const app = buildApp(mockFetch(403, { error: 'invalid_api_key' }));
    await request(app).post('/trakt/token').send({ code: 'test-code' });
    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining('HTTP 403'));
    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining('invalid_api_key'));
  });

  it('logs TRAKT_CLIENT_ID hint on 403 for token exchange', async () => {
    const app = buildApp(mockFetch(403, { error: 'invalid_api_key' }));
    await request(app).post('/trakt/token').send({ code: 'test-code' });
    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining('TRAKT_CLIENT_ID'));
  });

  it('logs non-JSON body snippet on token exchange when response is HTML', async () => {
    const app = buildApp(mockFetchHtml(503));
    await request(app).post('/trakt/token').send({ code: 'test-code' });
    const allErrors = errorSpy.mock.calls.map(c => c.join(' '));
    expect(allErrors.some(l => l.includes('<html>') || l.includes('Error'))).toBe(true);
  });

  it('does not log error for HTTP 400 during token exchange (pending polling)', async () => {
    const app = buildApp(mockFetch(400, { error: 'pending' }));
    await request(app).post('/trakt/token').send({ code: 'test-code' });
    expect(errorSpy).not.toHaveBeenCalled();
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('logs warn (not error) for HTTP 410/418/429 on token exchange', async () => {
    for (const status of [410, 418, 429]) {
      errorSpy.mockClear();
      warnSpy.mockClear();
      const app = buildApp(mockFetch(status, { error: 'status_error' }));
      await request(app).post('/trakt/token').send({ code: 'test-code' });
      expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining(`HTTP ${status}`));
      expect(errorSpy).not.toHaveBeenCalled();
    }
  });

  it('logs TRAKT_CLIENT_ID hint on 403 for token refresh', async () => {
    const app = buildApp(mockFetch(403, { error: 'invalid_api_key' }));
    await request(app).post('/trakt/token/refresh').send({ refresh_token: 'old-token' });
    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining('TRAKT_CLIENT_ID'));
  });
});

// ── Token refresh endpoint ────────────────────────────────────────────────────

describe('POST /trakt/token/refresh', () => {
  it('returns 400 when refresh_token is missing', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app).post('/trakt/token/refresh').send({});
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('Missing refresh_token');
  });

  it('refreshes tokens successfully', async () => {
    const app = buildApp(mockFetch(200, {
      access_token: 'new-acc',
      refresh_token: 'new-ref',
      expires_in: 7776000,
    }));
    const res = await request(app)
      .post('/trakt/token/refresh')
      .send({ refresh_token: 'old-token' });
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ access_token: 'new-acc', refresh_token: 'new-ref', expires_in: 7776000 });
  });

  it('forwards upstream 401 from Trakt', async () => {
    const res = await request(buildApp(mockFetch(401, { error: 'invalid_token' })))
      .post('/trakt/token/refresh')
      .send({ refresh_token: 'expired-token' });
    expect(res.status).toBe(401);
  });

  it('returns 502 on network error', async () => {
    const failFetch = vi.fn().mockRejectedValue(new Error('ETIMEDOUT'));
    const res = await request(buildApp(failFetch))
      .post('/trakt/token/refresh')
      .send({ refresh_token: 'old-token' });
    expect(res.status).toBe(502);
  });

  it('returns 502 when Trakt responds with non-JSON', async () => {
    const res = await request(buildApp(mockFetchHtml(503)))
      .post('/trakt/token/refresh')
      .send({ refresh_token: 'old-token' });
    expect(res.status).toBe(502);
    expect(res.body.error).toMatch(/non-JSON/i);
  });
});

// ── Unknown routes ────────────────────────────────────────────────────────────

describe('Unknown routes', () => {
  it('returns 404 for unregistered paths', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app).get('/does-not-exist');
    expect(res.status).toBe(404);
  });
});
