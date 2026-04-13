import { describe, it, expect, vi, beforeEach } from 'vitest';
import request from 'supertest';
import { createApp } from '../app.js';

/** Helper: build a mock fetch that resolves with the given status and body. */
function mockFetch(status, body) {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  });
}

/** Default config with fake credentials and a mock fetch. */
function buildApp(fetchFn) {
  return createApp({
    clientId: 'test-client-id',
    clientSecret: 'test-client-secret',
    traktApi: 'https://api.trakt.tv',
    fetchFn,
  });
}

// ── Health endpoint ─────────────────────────────────────────────────────────

describe('GET /health', () => {
  it('returns 200 with status ok', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app).get('/health');
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ status: 'ok' });
  });
});

// ── Token exchange endpoint ─────────────────────────────────────────────────

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
    const res = await request(app)
      .post('/trakt/token')
      .send({});
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('Missing code');
  });

  it('exchanges code for tokens successfully', async () => {
    const res = await request(app)
      .post('/trakt/token')
      .send({ code: 'device-code-abc' });

    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      access_token: 'acc-123',
      refresh_token: 'ref-456',
      expires_in: 7776000,
      token_type: 'Bearer',
      scope: 'public',
    });
  });

  it('sends correct payload to Trakt API', async () => {
    await request(app)
      .post('/trakt/token')
      .send({ code: 'device-code-abc' });

    expect(fetchFn).toHaveBeenCalledWith(
      'https://api.trakt.tv/oauth/device/token',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          code: 'device-code-abc',
          client_id: 'test-client-id',
          client_secret: 'test-client-secret',
        }),
      }),
    );
  });

  it('does not leak client_secret in response', async () => {
    const res = await request(app)
      .post('/trakt/token')
      .send({ code: 'device-code-abc' });

    expect(res.body).not.toHaveProperty('client_secret');
    expect(JSON.stringify(res.body)).not.toContain('test-client-secret');
  });

  it('forwards upstream error status from Trakt', async () => {
    const errorFetch = mockFetch(400, { error: 'invalid_grant' });
    const errorApp = buildApp(errorFetch);

    const res = await request(errorApp)
      .post('/trakt/token')
      .send({ code: 'bad-code' });

    expect(res.status).toBe(400);
    expect(res.body).toEqual({ error: 'invalid_grant' });
  });

  it('returns 502 when fetch throws a network error', async () => {
    const failFetch = vi.fn().mockRejectedValue(new Error('ECONNREFUSED'));
    const failApp = buildApp(failFetch);

    const res = await request(failApp)
      .post('/trakt/token')
      .send({ code: 'device-code-abc' });

    expect(res.status).toBe(502);
    expect(res.body).toEqual({ error: 'Upstream error' });
  });

  it('returns Trakt 429 status when upstream rate-limits', async () => {
    const rateFetch = mockFetch(429, { error: 'rate_limit_exceeded' });
    const rateApp = buildApp(rateFetch);

    const res = await request(rateApp)
      .post('/trakt/token')
      .send({ code: 'device-code-abc' });

    expect(res.status).toBe(429);
    expect(res.body).toEqual({ error: 'rate_limit_exceeded' });
  });
});

// ── Token refresh endpoint ──────────────────────────────────────────────────

describe('POST /trakt/token/refresh', () => {
  let fetchFn;
  let app;

  beforeEach(() => {
    fetchFn = mockFetch(200, {
      access_token: 'new-acc-789',
      refresh_token: 'new-ref-012',
      expires_in: 7776000,
    });
    app = buildApp(fetchFn);
  });

  it('returns 400 when refresh_token is missing', async () => {
    const res = await request(app)
      .post('/trakt/token/refresh')
      .send({});
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('Missing refresh_token');
  });

  it('refreshes tokens successfully', async () => {
    const res = await request(app)
      .post('/trakt/token/refresh')
      .send({ refresh_token: 'old-ref-token' });

    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      access_token: 'new-acc-789',
      refresh_token: 'new-ref-012',
      expires_in: 7776000,
    });
  });

  it('sends correct payload to Trakt API', async () => {
    await request(app)
      .post('/trakt/token/refresh')
      .send({ refresh_token: 'old-ref-token' });

    expect(fetchFn).toHaveBeenCalledWith(
      'https://api.trakt.tv/oauth/token',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          refresh_token: 'old-ref-token',
          client_id: 'test-client-id',
          client_secret: 'test-client-secret',
          grant_type: 'refresh_token',
        }),
      }),
    );
  });

  it('does not leak client_secret in response', async () => {
    const res = await request(app)
      .post('/trakt/token/refresh')
      .send({ refresh_token: 'old-ref-token' });

    expect(res.body).not.toHaveProperty('client_secret');
    expect(JSON.stringify(res.body)).not.toContain('test-client-secret');
  });

  it('forwards upstream error status from Trakt', async () => {
    const errorFetch = mockFetch(401, { error: 'invalid_token' });
    const errorApp = buildApp(errorFetch);

    const res = await request(errorApp)
      .post('/trakt/token/refresh')
      .send({ refresh_token: 'expired-token' });

    expect(res.status).toBe(401);
    expect(res.body).toEqual({ error: 'invalid_token' });
  });

  it('returns 502 when fetch throws a network error', async () => {
    const failFetch = vi.fn().mockRejectedValue(new Error('ETIMEDOUT'));
    const failApp = buildApp(failFetch);

    const res = await request(failApp)
      .post('/trakt/token/refresh')
      .send({ refresh_token: 'old-ref-token' });

    expect(res.status).toBe(502);
    expect(res.body).toEqual({ error: 'Upstream error' });
  });
});

// ── Unknown routes ──────────────────────────────────────────────────────────

describe('Unknown routes', () => {
  it('returns 404 for unregistered paths', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app).get('/does-not-exist');
    expect(res.status).toBe(404);
  });
});
