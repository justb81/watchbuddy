import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import request from 'supertest';
import { createApp } from '../app.js';

/** Helper: build a mock fetch that resolves with the given status and body. */
function mockFetch(status, body, headers = new Map()) {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
    headers: { forEach: (cb) => headers.forEach((v, k) => cb(v, k)) },
  });
}

/** Helper: build a mock fetch that resolves with the given status but returns non-JSON (HTML) body. */
function mockFetchHtml(status) {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.reject(new SyntaxError("Unexpected token '<', \"<html>...\" is not valid JSON")),
    headers: { forEach: (cb) => new Map().forEach((v, k) => cb(v, k)) },
  });
}

/** Default config with fake credentials and a mock fetch. */
function buildApp(fetchFn, overrides = {}) {
  return createApp({
    clientId: 'test-client-id',
    clientSecret: 'test-client-secret',
    traktApi: 'https://api.trakt.tv',
    fetchFn,
    ...overrides,
  });
}

// ── Health endpoint ─────────────────────────────────────────────────────────

describe('GET /health', () => {
  it('returns 503 with status starting before verification', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app).get('/health');
    expect(res.status).toBe(503);
    expect(res.body).toEqual({ status: 'starting', trakt: 'pending' });
  });

  it('returns 200 with status ok after successful verification', async () => {
    const app = buildApp(mockFetch(200, {}));
    await app.verifyCredentials();
    const res = await request(app).get('/health');
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ status: 'ok', trakt: 'connected', validated: 'client_id_via_oauth' });
  });
});

// ── Security headers ───────────────────────────────────────────────────────

describe('Security headers (helmet)', () => {
  it('sets security headers via helmet', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app).get('/health');
    expect(res.headers['x-content-type-options']).toBe('nosniff');
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
        headers: {
          'Content-Type': 'application/json',
          'trakt-api-key': 'test-client-id',
          'trakt-api-version': '2',
          'User-Agent': 'WatchBuddy/1.0.0',
        },
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

  it('returns 400 when code is not a string', async () => {
    const res = await request(app)
      .post('/trakt/token')
      .send({ code: 12345 });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('code must be a string');
  });

  it('returns 400 when code exceeds max length', async () => {
    const res = await request(app)
      .post('/trakt/token')
      .send({ code: 'a'.repeat(257) });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('code exceeds max length');
  });

  it('returns 400 when code contains invalid characters', async () => {
    const res = await request(app)
      .post('/trakt/token')
      .send({ code: 'bad code!@#' });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('code contains invalid characters');
  });

  it('returns 504 when upstream fetch times out', async () => {
    const hangingFetch = vi.fn().mockImplementation((_url, options) => {
      return new Promise((_resolve, reject) => {
        if (options?.signal) {
          options.signal.addEventListener('abort', () => {
            const err = new Error('The operation was aborted');
            err.name = 'AbortError';
            reject(err);
          });
        }
      });
    });
    const timeoutApp = buildApp(hangingFetch, { fetchTimeoutMs: 50 });

    const res = await request(timeoutApp)
      .post('/trakt/token')
      .send({ code: 'device-code-abc' });

    expect(res.status).toBe(504);
    expect(res.body).toEqual({ error: 'Upstream timeout' });
  });

  it('returns 502 when Trakt responds with non-JSON (HTML) on a 2xx status', async () => {
    const htmlFetch = mockFetchHtml(200);
    const htmlApp = buildApp(htmlFetch);

    const res = await request(htmlApp)
      .post('/trakt/token')
      .send({ code: 'device-code-abc' });

    expect(res.status).toBe(502);
    expect(res.body.error).toMatch(/non-JSON/i);
    expect(res.body.error).toContain('200');
  });

  it('returns 502 when Trakt responds with non-JSON (HTML) on an error status', async () => {
    const htmlFetch = mockFetchHtml(503);
    const htmlApp = buildApp(htmlFetch);

    const res = await request(htmlApp)
      .post('/trakt/token')
      .send({ code: 'device-code-abc' });

    expect(res.status).toBe(502);
    expect(res.body.error).toMatch(/non-JSON/i);
    expect(res.body.error).toContain('503');
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
      token_type: 'Bearer',
      scope: 'public',
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
      token_type: 'Bearer',
      scope: 'public',
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
        headers: {
          'Content-Type': 'application/json',
          'trakt-api-key': 'test-client-id',
          'trakt-api-version': '2',
          'User-Agent': 'WatchBuddy/1.0.0',
        },
        body: JSON.stringify({
          refresh_token: 'old-ref-token',
          client_id: 'test-client-id',
          client_secret: 'test-client-secret',
          redirect_uri: 'urn:ietf:wg:oauth:2.0:oob',
          grant_type: 'refresh_token',
        }),
      }),
    );
  });

  it('includes redirect_uri in the refresh payload', async () => {
    await request(app)
      .post('/trakt/token/refresh')
      .send({ refresh_token: 'old-ref-token' });

    const callBody = JSON.parse(fetchFn.mock.calls[0][1].body);
    expect(callBody.redirect_uri).toBe('urn:ietf:wg:oauth:2.0:oob');
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

  it('returns 400 when refresh_token is not a string', async () => {
    const res = await request(app)
      .post('/trakt/token/refresh')
      .send({ refresh_token: ['array'] });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('refresh_token must be a string');
  });

  it('returns 400 when refresh_token exceeds max length', async () => {
    const res = await request(app)
      .post('/trakt/token/refresh')
      .send({ refresh_token: 'x'.repeat(257) });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('refresh_token exceeds max length');
  });

  it('returns 400 when refresh_token contains invalid characters', async () => {
    const res = await request(app)
      .post('/trakt/token/refresh')
      .send({ refresh_token: 'has spaces and $pecial' });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('refresh_token contains invalid characters');
  });

  it('returns 504 when upstream fetch times out', async () => {
    const hangingFetch = vi.fn().mockImplementation((_url, options) => {
      return new Promise((_resolve, reject) => {
        if (options?.signal) {
          options.signal.addEventListener('abort', () => {
            const err = new Error('The operation was aborted');
            err.name = 'AbortError';
            reject(err);
          });
        }
      });
    });
    const timeoutApp = buildApp(hangingFetch, { fetchTimeoutMs: 50 });

    const res = await request(timeoutApp)
      .post('/trakt/token/refresh')
      .send({ refresh_token: 'old-ref-token' });

    expect(res.status).toBe(504);
    expect(res.body).toEqual({ error: 'Upstream timeout' });
  });

  it('returns 502 when Trakt responds with non-JSON (HTML) on a 2xx status', async () => {
    const htmlFetch = mockFetchHtml(200);
    const htmlApp = buildApp(htmlFetch);

    const res = await request(htmlApp)
      .post('/trakt/token/refresh')
      .send({ refresh_token: 'old-ref-token' });

    expect(res.status).toBe(502);
    expect(res.body.error).toMatch(/non-JSON/i);
    expect(res.body.error).toContain('200');
  });

  it('returns 502 when Trakt responds with non-JSON (HTML) on an error status', async () => {
    const htmlFetch = mockFetchHtml(503);
    const htmlApp = buildApp(htmlFetch);

    const res = await request(htmlApp)
      .post('/trakt/token/refresh')
      .send({ refresh_token: 'old-ref-token' });

    expect(res.status).toBe(502);
    expect(res.body.error).toMatch(/non-JSON/i);
    expect(res.body.error).toContain('503');
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

// ── Credential verification ────────────────────────────────────────────────

describe('Credential verification', () => {
  let logSpy;
  let errorSpy;

  beforeEach(() => {
    logSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    logSpy.mockRestore();
    errorSpy.mockRestore();
  });

  it('exposes verifyCredentials as a function on the app', () => {
    const app = buildApp(mockFetch(200, {}));
    expect(typeof app.verifyCredentials).toBe('function');
  });

  it('sends correct POST to Trakt /oauth/device/code', async () => {
    const fetchFn = mockFetch(200, {
      device_code: 'mock-device-code',
      user_code: 'ABCD1234',
      verification_url: 'https://trakt.tv/activate',
      expires_in: 600,
      interval: 5,
    });
    const app = buildApp(fetchFn);
    await app.verifyCredentials();
    expect(fetchFn).toHaveBeenCalledWith(
      'https://api.trakt.tv/oauth/device/code',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Content-Type': 'application/json',
          'trakt-api-key': 'test-client-id',
          'trakt-api-version': '2',
          'User-Agent': 'WatchBuddy/1.0.0',
        }),
        body: JSON.stringify({ client_id: 'test-client-id' }),
      }),
    );
  });

  it('health returns 200 connected after successful verification', async () => {
    const app = buildApp(mockFetch(200, []));
    await app.verifyCredentials();
    const res = await request(app).get('/health');
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ status: 'ok', trakt: 'connected', validated: 'client_id_via_oauth' });
  });

  it('health returns 503 invalid_client_id when Trakt returns 403', async () => {
    const app = buildApp(mockFetch(403, { error: 'invalid_api_key' }));
    await app.verifyCredentials();
    const res = await request(app).get('/health');
    expect(res.status).toBe(503);
    expect(res.body.status).toBe('unhealthy');
    expect(res.body.trakt).toBe('invalid_client_id');
    expect(res.body.error).toMatch(/TRAKT_CLIENT_ID/);
  });

  it('health returns 503 with trakt_http_500 on server error', async () => {
    const app = buildApp(mockFetch(500, {}));
    await app.verifyCredentials();
    app.clearRetryTimer();
    const res = await request(app).get('/health');
    expect(res.status).toBe(503);
    expect(res.body.status).toBe('unhealthy');
    expect(res.body.trakt).toBe('trakt_http_500');
  });

  it('health returns 503 timeout when verification times out', async () => {
    const hangingFetch = vi.fn().mockImplementation((_url, options) => {
      return new Promise((_resolve, reject) => {
        if (options?.signal) {
          options.signal.addEventListener('abort', () => {
            const err = new Error('The operation was aborted');
            err.name = 'AbortError';
            reject(err);
          });
        }
      });
    });
    const app = buildApp(hangingFetch, { fetchTimeoutMs: 50 });
    await app.verifyCredentials();
    app.clearRetryTimer();
    const res = await request(app).get('/health');
    expect(res.status).toBe(503);
    expect(res.body.status).toBe('unhealthy');
    expect(res.body.trakt).toBe('timeout');
  });

  it('health returns 503 network_error when fetch rejects', async () => {
    const failFetch = vi.fn().mockRejectedValue(new Error('ECONNREFUSED'));
    const app = buildApp(failFetch);
    await app.verifyCredentials();
    app.clearRetryTimer();
    const res = await request(app).get('/health');
    expect(res.status).toBe(503);
    expect(res.body.status).toBe('unhealthy');
    expect(res.body.trakt).toBe('network_error');
    expect(res.body.error).toBe('ECONNREFUSED');
  });

  it('logs success message on valid credentials', async () => {
    const app = buildApp(mockFetch(200, []));
    await app.verifyCredentials();
    expect(logSpy).toHaveBeenCalledWith('Trakt credential verification: OK');
  });

  it('logs error with TRAKT_CLIENT_ID hint on 403', async () => {
    const app = buildApp(mockFetch(403, {}));
    await app.verifyCredentials();
    expect(errorSpy).toHaveBeenCalledWith(
      expect.stringContaining('TRAKT_CLIENT_ID'),
    );
  });

  it('health returns 503 invalid_client_id when Trakt returns 401', async () => {
    const app = buildApp(mockFetch(401, { error: 'unauthorized' }));
    await app.verifyCredentials();
    const res = await request(app).get('/health');
    expect(res.status).toBe(503);
    expect(res.body.status).toBe('unhealthy');
    expect(res.body.trakt).toBe('invalid_client_id');
    expect(res.body.error).toMatch(/TRAKT_CLIENT_ID/);
  });

  it('logs response body snippet when credential check fails', async () => {
    const app = buildApp(mockFetch(403, { error: 'invalid_api_key' }));
    await app.verifyCredentials();
    expect(errorSpy).toHaveBeenCalledWith(
      expect.stringContaining('invalid_api_key'),
    );
  });

  it('handles non-JSON response gracefully during credential check', async () => {
    const app = buildApp(mockFetchHtml(200));
    await app.verifyCredentials();
    const res = await request(app).get('/health');
    expect(res.status).toBe(200);
    expect(res.body.trakt).toBe('connected');
  });
});

// ── Credential verification retry ─────────────────────────────────────────

describe('Credential verification retry', () => {
  let logSpy;
  let errorSpy;

  beforeEach(() => {
    vi.useFakeTimers();
    logSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.useRealTimers();
    logSpy.mockRestore();
    errorSpy.mockRestore();
  });

  it('schedules retry after transient HTTP error (e.g. 503)', async () => {
    const fetchFn = mockFetch(503, {});
    const app = buildApp(fetchFn);

    await app.verifyCredentials();
    expect(fetchFn).toHaveBeenCalledTimes(1);

    // Advance past first retry delay (5s)
    fetchFn.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve([]),
      headers: { forEach: (cb) => new Map().forEach((v, k) => cb(v, k)) },
    });
    await vi.advanceTimersByTimeAsync(5_000);

    expect(fetchFn).toHaveBeenCalledTimes(2);
    app.clearRetryTimer();
  });

  it('does not retry on 401/403 (invalid credentials)', async () => {
    const fetchFn = mockFetch(403, { error: 'invalid_api_key' });
    const app = buildApp(fetchFn);

    await app.verifyCredentials();
    expect(fetchFn).toHaveBeenCalledTimes(1);

    // Advance well past any retry delay
    await vi.advanceTimersByTimeAsync(120_000);

    // No retry should have been scheduled
    expect(fetchFn).toHaveBeenCalledTimes(1);
    app.clearRetryTimer();
  });

  it('schedules retry after network error', async () => {
    const fetchFn = vi.fn().mockRejectedValue(new Error('ECONNREFUSED'));
    const app = buildApp(fetchFn);

    await app.verifyCredentials();
    expect(fetchFn).toHaveBeenCalledTimes(1);

    // Advance past first retry delay (5s)
    fetchFn.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve([]),
      headers: { forEach: (cb) => new Map().forEach((v, k) => cb(v, k)) },
    });
    await vi.advanceTimersByTimeAsync(5_000);

    expect(fetchFn).toHaveBeenCalledTimes(2);
    app.clearRetryTimer();
  });

  it('schedules retry after timeout', async () => {
    const abortErr = new Error('The operation was aborted');
    abortErr.name = 'AbortError';
    const fetchFn = vi.fn().mockRejectedValue(abortErr);
    const app = buildApp(fetchFn);

    await app.verifyCredentials();
    expect(fetchFn).toHaveBeenCalledTimes(1);

    // For the retry, return success
    fetchFn.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve([]),
      headers: { forEach: (cb) => new Map().forEach((v, k) => cb(v, k)) },
    });
    await vi.advanceTimersByTimeAsync(5_000);

    expect(fetchFn).toHaveBeenCalledTimes(2);
    app.clearRetryTimer();
  });

  it('recovers to healthy after retry succeeds', async () => {
    const fetchFn = vi.fn()
      .mockResolvedValueOnce({
        ok: false,
        status: 503,
        json: () => Promise.resolve({}),
        headers: { forEach: (cb) => new Map().forEach((v, k) => cb(v, k)) },
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: () => Promise.resolve([]),
        headers: { forEach: (cb) => new Map().forEach((v, k) => cb(v, k)) },
      });
    const app = buildApp(fetchFn);

    await app.verifyCredentials();
    let res = await request(app).get('/health');
    expect(res.status).toBe(503);
    expect(res.body.trakt).toBe('trakt_http_503');

    // Advance past first retry delay
    await vi.advanceTimersByTimeAsync(5_000);

    res = await request(app).get('/health');
    expect(res.status).toBe(200);
    expect(res.body.trakt).toBe('connected');
    app.clearRetryTimer();
  });

  it('uses increasing retry delays', async () => {
    // Always return 503 to keep retrying
    const fetchFn = mockFetch(503, {});
    const app = buildApp(fetchFn);

    await app.verifyCredentials();
    expect(fetchFn).toHaveBeenCalledTimes(1);

    // First retry at 5s
    await vi.advanceTimersByTimeAsync(5_000);
    expect(fetchFn).toHaveBeenCalledTimes(2);

    // Second retry at 15s
    await vi.advanceTimersByTimeAsync(15_000);
    expect(fetchFn).toHaveBeenCalledTimes(3);

    // Third retry at 30s
    await vi.advanceTimersByTimeAsync(30_000);
    expect(fetchFn).toHaveBeenCalledTimes(4);

    // Fourth retry at 60s
    await vi.advanceTimersByTimeAsync(60_000);
    expect(fetchFn).toHaveBeenCalledTimes(5);

    app.clearRetryTimer();
  });
});

// ── Error logging ──────────────────────────────────────────────────────────

describe('Error logging improvements', () => {
  let errorSpy;

  beforeEach(() => {
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    errorSpy.mockRestore();
  });

  it('logs body snippet when Trakt returns non-OK on token exchange', async () => {
    const app = buildApp(mockFetch(403, { error: 'invalid_api_key' }));
    await request(app).post('/trakt/token').send({ code: 'test-code' });
    expect(errorSpy).toHaveBeenCalledWith(
      expect.stringContaining('HTTP 403'),
    );
    expect(errorSpy).toHaveBeenCalledWith(
      expect.stringContaining('invalid_api_key'),
    );
  });

  it('logs TRAKT_CLIENT_ID hint on 403 for token exchange', async () => {
    const app = buildApp(mockFetch(403, { error: 'invalid_api_key' }));
    await request(app).post('/trakt/token').send({ code: 'test-code' });
    expect(errorSpy).toHaveBeenCalledWith(
      expect.stringContaining('TRAKT_CLIENT_ID'),
    );
  });

  it('logs TRAKT_CLIENT_ID hint on 403 for token refresh', async () => {
    const app = buildApp(mockFetch(403, { error: 'invalid_api_key' }));
    await request(app).post('/trakt/token/refresh').send({ refresh_token: 'old-token' });
    expect(errorSpy).toHaveBeenCalledWith(
      expect.stringContaining('TRAKT_CLIENT_ID'),
    );
  });

  it('does not log error or warn for HTTP 400 on token exchange (pending during polling)', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const app = buildApp(mockFetch(400, { error: 'pending' }));
    await request(app).post('/trakt/token').send({ code: 'test-code' });
    expect(errorSpy).not.toHaveBeenCalled();
    expect(warnSpy).not.toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('logs warn (not error) for device flow status codes 410 and 418', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const app410 = buildApp(mockFetch(410, { error: 'expired' }));
    await request(app410).post('/trakt/token').send({ code: 'test-code' });
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('HTTP 410'));
    expect(errorSpy).not.toHaveBeenCalled();

    warnSpy.mockClear();
    errorSpy.mockClear();

    const app418 = buildApp(mockFetch(418, { error: 'denied' }));
    await request(app418).post('/trakt/token').send({ code: 'test-code' });
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('HTTP 418'));
    expect(errorSpy).not.toHaveBeenCalled();

    warnSpy.mockRestore();
  });

  it('logs warn (not error) for HTTP 429 slow down', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const app = buildApp(mockFetch(429, { error: 'slow_down' }));
    await request(app).post('/trakt/token').send({ code: 'test-code' });
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('HTTP 429'));
    expect(errorSpy).not.toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('logs network error code on ECONNREFUSED for token exchange', async () => {
    const err = new Error('connect ECONNREFUSED');
    err.code = 'ECONNREFUSED';
    const failFetch = vi.fn().mockRejectedValue(err);
    const app = buildApp(failFetch);
    await request(app).post('/trakt/token').send({ code: 'test-code' });
    expect(errorSpy).toHaveBeenCalledWith(
      expect.stringContaining('network error (ECONNREFUSED)'),
      expect.any(String),
    );
  });

  it('logs network error code on ECONNREFUSED for token refresh', async () => {
    const err = new Error('connect ECONNREFUSED');
    err.code = 'ECONNREFUSED';
    const failFetch = vi.fn().mockRejectedValue(err);
    const app = buildApp(failFetch);
    await request(app).post('/trakt/token/refresh').send({ refresh_token: 'old-token' });
    expect(errorSpy).toHaveBeenCalledWith(
      expect.stringContaining('network error (ECONNREFUSED)'),
      expect.any(String),
    );
  });
});

// ── Debug logging ───────────────────────────────────────────────────────────

describe('Debug logging (debug: true)', () => {
  let debugSpy;

  beforeEach(() => {
    debugSpy = vi.spyOn(console, 'debug').mockImplementation(() => {});
  });

  afterEach(() => {
    debugSpy.mockRestore();
  });

  it('logs a debug line for GET /health when debug is enabled', async () => {
    const app = buildApp(mockFetch(200, {}), { debug: true });
    await app.verifyCredentials();
    await request(app).get('/health');
    const healthLog = debugSpy.mock.calls.find(([msg]) =>
      /\[DEBUG\].*GET.*\/health.*200/.test(msg),
    );
    expect(healthLog).toBeDefined();
  });

  it('log line includes method, path, status, and timing', async () => {
    const app = buildApp(mockFetch(200, {}), { debug: true });
    await request(app).post('/trakt/token').send({ code: 'device-code-abc' });
    // The debug middleware logs request timing; logTraktCall also logs Trakt API details
    const requestLog = debugSpy.mock.calls.find(([msg]) =>
      /\[DEBUG\].*POST.*\/trakt\/token.*\d+ms/.test(msg),
    );
    expect(requestLog).toBeDefined();
  });

  it('logs debug line even when the endpoint returns an error status', async () => {
    const app = buildApp(mockFetch(400, { error: 'invalid_grant' }), { debug: true });
    await request(app).post('/trakt/token').send({ code: 'bad-code' });
    const requestLog = debugSpy.mock.calls.find(([msg]) =>
      /\[DEBUG\].*400.*ms/.test(msg),
    );
    expect(requestLog).toBeDefined();
  });

  it('logs one line per request for multiple requests', async () => {
    const app = buildApp(mockFetch(200, {}), { debug: true });
    await request(app).get('/health');
    await request(app).get('/health');
    expect(debugSpy).toHaveBeenCalledTimes(2);
  });
});

describe('Debug logging (debug: false / default)', () => {
  let debugSpy;

  beforeEach(() => {
    debugSpy = vi.spyOn(console, 'debug').mockImplementation(() => {});
  });

  afterEach(() => {
    debugSpy.mockRestore();
  });

  it('does not log any debug output when debug is disabled (explicit false)', async () => {
    const app = buildApp(mockFetch(200, {}), { debug: false });
    await request(app).get('/health');
    expect(debugSpy).not.toHaveBeenCalled();
  });

  it('does not log any debug output when debug option is omitted', async () => {
    const app = buildApp(mockFetch(200, {}));
    await request(app).get('/health');
    expect(debugSpy).not.toHaveBeenCalled();
  });
});

// ── Trakt API call debug logging ──────────────────────────────────────────

describe('Debug logging — Trakt API call details (debug: true)', () => {
  let debugSpy;

  beforeEach(() => {
    debugSpy = vi.spyOn(console, 'debug').mockImplementation(() => {});
  });

  afterEach(() => {
    debugSpy.mockRestore();
  });

  it('logs outgoing request details for token exchange', async () => {
    const app = buildApp(mockFetch(200, {
      access_token: 'acc-123',
      refresh_token: 'ref-456',
      expires_in: 7776000,
      token_type: 'Bearer',
      scope: 'public',
    }), { debug: true });

    await request(app).post('/trakt/token').send({ code: 'device-code-abc' });

    const allLogs = debugSpy.mock.calls.map(c => c.join(' '));
    expect(allLogs.some(l => l.includes('Token exchange') && l.includes('/oauth/device/token'))).toBe(true);
    expect(allLogs.some(l => l.includes('Token exchange') && l.includes('request body'))).toBe(true);
    expect(allLogs.some(l => l.includes('Token exchange') && l.includes('response status') && l.includes('200'))).toBe(true);
    expect(allLogs.some(l => l.includes('Token exchange') && l.includes('response body'))).toBe(true);
  });

  it('logs outgoing request details for token refresh', async () => {
    const app = buildApp(mockFetch(200, {
      access_token: 'new-acc-789',
      refresh_token: 'new-ref-012',
      expires_in: 7776000,
    }), { debug: true });

    await request(app).post('/trakt/token/refresh').send({ refresh_token: 'old-ref-token' });

    const allLogs = debugSpy.mock.calls.map(c => c.join(' '));
    expect(allLogs.some(l => l.includes('Token refresh') && l.includes('/oauth/token'))).toBe(true);
    expect(allLogs.some(l => l.includes('Token refresh') && l.includes('request body'))).toBe(true);
    expect(allLogs.some(l => l.includes('Token refresh') && l.includes('response status') && l.includes('200'))).toBe(true);
  });

  it('masks client_secret in debug logs', async () => {
    const app = buildApp(mockFetch(200, {
      access_token: 'acc-123',
      refresh_token: 'ref-456',
      expires_in: 7776000,
      token_type: 'Bearer',
      scope: 'public',
    }), { debug: true });

    await request(app).post('/trakt/token').send({ code: 'device-code-abc' });

    const allLogs = debugSpy.mock.calls.map(c => c.join(' '));
    const bodyLog = allLogs.find(l => l.includes('request body'));
    expect(bodyLog).toBeDefined();
    expect(bodyLog).not.toContain('test-client-secret');
    expect(bodyLog).toContain('test***');
  });

  it('masks access_token and refresh_token in response body debug logs', async () => {
    const app = buildApp(mockFetch(200, {
      access_token: 'acc-full-secret-token',
      refresh_token: 'ref-full-secret-token',
      expires_in: 7776000,
      token_type: 'Bearer',
      scope: 'public',
    }), { debug: true });

    await request(app).post('/trakt/token').send({ code: 'device-code-abc' });

    const allLogs = debugSpy.mock.calls.map(c => c.join(' '));
    const bodyLog = allLogs.find(l => l.includes('response body'));
    expect(bodyLog).toBeDefined();
    expect(bodyLog).not.toContain('acc-full-secret-token');
    expect(bodyLog).not.toContain('ref-full-secret-token');
    expect(bodyLog).toContain('acc-***');
    expect(bodyLog).toContain('ref-***');
  });

  it('logs response headers when present', async () => {
    const headers = new Map([
      ['x-ratelimit-limit', '1000'],
      ['content-type', 'application/json'],
    ]);
    const app = buildApp(mockFetch(200, {
      access_token: 'acc-123',
      refresh_token: 'ref-456',
      expires_in: 7776000,
      token_type: 'Bearer',
      scope: 'public',
    }, headers), { debug: true });

    await request(app).post('/trakt/token').send({ code: 'device-code-abc' });

    const allLogs = debugSpy.mock.calls.map(c => c.join(' '));
    const headerLog = allLogs.find(l => l.includes('response headers'));
    expect(headerLog).toBeDefined();
    expect(headerLog).toContain('x-ratelimit-limit');
    expect(headerLog).toContain('1000');
  });

  it('logs response body for credential check when debug is enabled', async () => {
    const logSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
    const app = buildApp(mockFetch(200, {
      device_code: 'mock-device-code',
      user_code: 'ABCD1234',
      verification_url: 'https://trakt.tv/activate',
      expires_in: 600,
      interval: 5,
    }), { debug: true });
    await app.verifyCredentials();
    logSpy.mockRestore();

    const allLogs = debugSpy.mock.calls.map(c => c.join(' '));
    expect(allLogs.some(l => l.includes('Credential check') && l.includes('response body'))).toBe(true);
  });

  it('does not log Trakt API call details when debug is false', async () => {
    const app = buildApp(mockFetch(200, {
      access_token: 'acc-123',
      refresh_token: 'ref-456',
      expires_in: 7776000,
      token_type: 'Bearer',
      scope: 'public',
    }));

    await request(app).post('/trakt/token').send({ code: 'device-code-abc' });

    const allLogs = debugSpy.mock.calls.map(c => c.join(' '));
    expect(allLogs.some(l => l.includes('Token exchange'))).toBe(false);
  });
});
