import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import request from 'supertest';
import { createApp } from '../app.js';

/** Helper: build a mock fetch that resolves with the given status and body. */
function mockFetch(status, body, headers = new Map()) {
  const text = JSON.stringify(body);
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(text),
    headers: { forEach: (cb) => headers.forEach((v, k) => cb(v, k)) },
  });
}

/** Helper: build a mock fetch that resolves with the given status but returns non-JSON (HTML) body. */
function mockFetchHtml(status) {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: () =>
      Promise.reject(new SyntaxError('Unexpected token \'<\', "<html>..." is not valid JSON')),
    text: () => Promise.resolve('<html><body>Oops</body></html>'),
    headers: { forEach: (cb) => new Map().forEach((v, k) => cb(v, k)) },
  });
}

/** Helper: build a mock fetch that resolves with the given status and an empty body. */
function mockFetchEmpty(status) {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.reject(new SyntaxError('Unexpected end of JSON input')),
    text: () => Promise.resolve(''),
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
    healthCacheTtlMs: 0, // disable caching in tests unless explicitly overridden
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
    expect(res.body).toEqual({
      status: 'ok',
      trakt: 'connected',
      validated: 'client_id_via_oauth',
    });
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

  it('sends correct payload to Trakt API', async () => {
    await request(app).post('/trakt/token').send({ code: 'device-code-abc' });

    expect(fetchFn).toHaveBeenCalledWith(
      'https://api.trakt.tv/oauth/device/token',
      expect.objectContaining({
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'trakt-api-key': 'test-client-id',
          'trakt-api-version': '2',
          'User-Agent': 'WatchBuddy/0.0.0',
        },
        body: JSON.stringify({
          code: 'device-code-abc',
          client_id: 'test-client-id',
          client_secret: 'test-client-secret',
        }),
      })
    );
  });

  it('does not leak client_secret in response', async () => {
    const res = await request(app).post('/trakt/token').send({ code: 'device-code-abc' });

    expect(res.body).not.toHaveProperty('client_secret');
    expect(JSON.stringify(res.body)).not.toContain('test-client-secret');
  });

  it('forwards upstream error status from Trakt', async () => {
    const errorFetch = mockFetch(400, { error: 'invalid_grant' });
    const errorApp = buildApp(errorFetch);

    const res = await request(errorApp).post('/trakt/token').send({ code: 'bad-code' });

    expect(res.status).toBe(400);
    expect(res.body).toEqual({ error: 'invalid_grant' });
  });

  it('returns 502 when fetch throws a network error', async () => {
    const failFetch = vi.fn().mockRejectedValue(new Error('ECONNREFUSED'));
    const failApp = buildApp(failFetch);

    const res = await request(failApp).post('/trakt/token').send({ code: 'device-code-abc' });

    expect(res.status).toBe(502);
    expect(res.body).toEqual({ error: 'Upstream error' });
  });

  it('returns Trakt 429 status when upstream rate-limits', async () => {
    const rateFetch = mockFetch(429, { error: 'rate_limit_exceeded' });
    const rateApp = buildApp(rateFetch);

    const res = await request(rateApp).post('/trakt/token').send({ code: 'device-code-abc' });

    expect(res.status).toBe(429);
    expect(res.body).toEqual({ error: 'rate_limit_exceeded' });
  });

  it('returns 400 when code is not a string', async () => {
    const res = await request(app).post('/trakt/token').send({ code: 12345 });
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
    const res = await request(app).post('/trakt/token').send({ code: 'bad code!@#' });
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

    const res = await request(timeoutApp).post('/trakt/token').send({ code: 'device-code-abc' });

    expect(res.status).toBe(504);
    expect(res.body).toEqual({ error: 'Upstream timeout' });
  });

  it('returns 502 when Trakt responds with non-JSON (HTML) on a 2xx status', async () => {
    const htmlFetch = mockFetchHtml(200);
    const htmlApp = buildApp(htmlFetch);

    const res = await request(htmlApp).post('/trakt/token').send({ code: 'device-code-abc' });

    expect(res.status).toBe(502);
    expect(res.body.error).toMatch(/non-JSON/i);
    expect(res.body.error).toContain('200');
  });

  it('returns 502 when Trakt responds with non-JSON (HTML) on an error status', async () => {
    const htmlFetch = mockFetchHtml(503);
    const htmlApp = buildApp(htmlFetch);

    const res = await request(htmlApp).post('/trakt/token').send({ code: 'device-code-abc' });

    expect(res.status).toBe(502);
    expect(res.body.error).toMatch(/non-JSON/i);
    expect(res.body.error).toContain('503');
  });

  it('returns 400 authorization_pending when Trakt responds 400 with an empty body', async () => {
    // Trakt's device-flow /oauth/device/token returns HTTP 400 with an empty body
    // while the user hasn't yet authorized on trakt.tv/activate. The proxy must
    // pass this through as 400 (not 502) so the client's polling loop keeps
    // polling instead of treating it as a network failure.
    const emptyFetch = mockFetchEmpty(400);
    const emptyApp = buildApp(emptyFetch);

    const res = await request(emptyApp).post('/trakt/token').send({ code: 'device-code-abc' });

    expect(res.status).toBe(400);
    expect(res.body).toEqual({ error: 'authorization_pending' });
  });

  it('returns 502 when Trakt responds with empty body on a 5xx status', async () => {
    const emptyFetch = mockFetchEmpty(500);
    const emptyApp = buildApp(emptyFetch);

    const res = await request(emptyApp).post('/trakt/token').send({ code: 'device-code-abc' });

    expect(res.status).toBe(502);
    expect(res.body.error).toMatch(/non-JSON/i);
    expect(res.body.error).toContain('500');
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
    const res = await request(app).post('/trakt/token/refresh').send({});
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
    await request(app).post('/trakt/token/refresh').send({ refresh_token: 'old-ref-token' });

    expect(fetchFn).toHaveBeenCalledWith(
      'https://api.trakt.tv/oauth/token',
      expect.objectContaining({
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'trakt-api-key': 'test-client-id',
          'trakt-api-version': '2',
          'User-Agent': 'WatchBuddy/0.0.0',
        },
        body: JSON.stringify({
          refresh_token: 'old-ref-token',
          client_id: 'test-client-id',
          client_secret: 'test-client-secret',
          redirect_uri: 'urn:ietf:wg:oauth:2.0:oob',
          grant_type: 'refresh_token',
        }),
      })
    );
  });

  it('includes redirect_uri in the refresh payload', async () => {
    await request(app).post('/trakt/token/refresh').send({ refresh_token: 'old-ref-token' });

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

// ── Server misconfiguration (missing/invalid credentials) ───────────────────

describe('POST /trakt/token — server_misconfigured', () => {
  let errorSpy;

  beforeEach(() => {
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    errorSpy.mockRestore();
  });

  it('returns 503 server_misconfigured when clientSecret is missing', async () => {
    const app = buildApp(mockFetch(200, {}), { clientSecret: '' });
    const res = await request(app).post('/trakt/token').send({ code: 'device-code-abc' });
    expect(res.status).toBe(503);
    expect(res.body).toEqual({ error: 'server_misconfigured' });
  });

  it('returns 503 server_misconfigured when clientSecret is undefined', async () => {
    const app = buildApp(mockFetch(200, {}), { clientSecret: undefined });
    const res = await request(app).post('/trakt/token').send({ code: 'device-code-abc' });
    expect(res.status).toBe(503);
    expect(res.body).toEqual({ error: 'server_misconfigured' });
  });

  it('returns 503 server_misconfigured after verifyCredentials receives 401', async () => {
    const app = buildApp(mockFetch(401, { error: 'unauthorized' }));
    await app.verifyCredentials();
    const res = await request(app).post('/trakt/token').send({ code: 'device-code-abc' });
    expect(res.status).toBe(503);
    expect(res.body).toEqual({ error: 'server_misconfigured' });
  });

  it('returns 503 server_misconfigured after verifyCredentials receives 403', async () => {
    const app = buildApp(mockFetch(403, { error: 'invalid_api_key' }));
    await app.verifyCredentials();
    const res = await request(app).post('/trakt/token').send({ code: 'device-code-abc' });
    expect(res.status).toBe(503);
    expect(res.body).toEqual({ error: 'server_misconfigured' });
  });

  it('logs an error when blocking token exchange due to misconfiguration', async () => {
    const app = buildApp(mockFetch(200, {}), { clientSecret: '' });
    await request(app).post('/trakt/token').send({ code: 'device-code-abc' });
    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining('misconfigured'));
  });

  it('health returns 503 misconfigured when clientSecret is missing', async () => {
    const app = buildApp(mockFetch(200, {}), { clientSecret: '' });
    const res = await request(app).get('/health');
    expect(res.status).toBe(503);
    expect(res.body.status).toBe('misconfigured');
    expect(res.body.error).toMatch(/TRAKT_CLIENT_SECRET/);
  });

  it('does not block token exchange when credentials are valid', async () => {
    const app = buildApp(
      mockFetch(200, {
        access_token: 'acc-123',
        refresh_token: 'ref-456',
        expires_in: 7776000,
        token_type: 'Bearer',
        scope: 'public',
      })
    );
    const res = await request(app).post('/trakt/token').send({ code: 'device-code-abc' });
    expect(res.status).toBe(200);
    expect(res.body.access_token).toBe('acc-123');
  });
});

// ── Unknown routes ──────────────────────────────────────────────────────────

describe('Unknown routes', () => {
  it('returns 404 with JSON body for unregistered paths', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app).get('/does-not-exist');
    expect(res.status).toBe(404);
    expect(res.headers['content-type']).toMatch(/application\/json/);
    expect(res.body).toEqual({ error: 'not_found' });
  });

  it('returns 404 JSON for unknown POST paths regardless of method', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app)
      .post('/no-such-endpoint')
      .set('Content-Type', 'application/json')
      .send({ foo: 'bar' });
    expect(res.status).toBe(404);
    expect(res.body).toEqual({ error: 'not_found' });
  });
});

describe('Global error handler', () => {
  let errorSpy;

  beforeEach(() => {
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    errorSpy.mockRestore();
  });

  // body-parser throws { type: 'encoding.unsupported' } for unrecognised
  // Content-Encoding values. The dedicated body-parser error handler in
  // app.js only catches entity.too.large / entity.parse.failed and forwards
  // every other type, which lands on the global error handler — exactly
  // the path we want to exercise.
  it('returns 500 JSON when an unhandled body-parser error reaches it', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app)
      .post('/trakt/token')
      .set('Content-Type', 'application/json')
      .set('Content-Encoding', 'utterly-invalid-encoding')
      .send('{"code":"abc"}');
    expect(res.status).toBe(500);
    expect(res.headers['content-type']).toMatch(/application\/json/);
    expect(res.body).toEqual({ error: 'internal_error' });
  });

  it('does not leak the underlying error message in the response body', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app)
      .post('/trakt/token')
      .set('Content-Type', 'application/json')
      .set('Content-Encoding', 'utterly-invalid-encoding')
      .send('{"code":"abc"}');
    const serialized = JSON.stringify(res.body);
    expect(serialized).not.toMatch(/encoding/i);
    expect(serialized).not.toMatch(/stack/i);
    expect(serialized).not.toMatch(/utterly-invalid-encoding/);
  });

  it('logs the original error so operators can diagnose failures', async () => {
    const app = buildApp(mockFetch(200, {}));
    await request(app)
      .post('/trakt/token')
      .set('Content-Type', 'application/json')
      .set('Content-Encoding', 'utterly-invalid-encoding')
      .send('{"code":"abc"}');
    const logged = errorSpy.mock.calls.some(([first]) =>
      typeof first === 'string' ? first.includes('Unhandled error') : false
    );
    expect(logged).toBe(true);
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
          'User-Agent': 'WatchBuddy/0.0.0',
        }),
        body: JSON.stringify({ client_id: 'test-client-id' }),
      })
    );
  });

  it('health returns 200 connected after successful verification', async () => {
    const app = buildApp(mockFetch(200, []));
    await app.verifyCredentials();
    const res = await request(app).get('/health');
    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      status: 'ok',
      trakt: 'connected',
      validated: 'client_id_via_oauth',
    });
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
      expect.any(Object)
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

  it('logs traktErrorCode in structured context when credential check fails', async () => {
    const app = buildApp(mockFetch(403, { error: 'invalid_api_key' }));
    await app.verifyCredentials();
    expect(errorSpy).toHaveBeenCalledWith(
      expect.stringContaining('TRAKT_CLIENT_ID'),
      expect.objectContaining({ traktErrorCode: 'invalid_api_key' })
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
    const fetchFn = vi
      .fn()
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

  it('logs traktErrorCode in structured context when Trakt returns non-OK on token exchange', async () => {
    const app = buildApp(mockFetch(403, { error: 'invalid_api_key' }));
    await request(app).post('/trakt/token').send({ code: 'test-code' });
    expect(errorSpy).toHaveBeenCalledWith(
      expect.stringContaining('HTTP 403'),
      expect.objectContaining({ status: 403, traktErrorCode: 'invalid_api_key' })
    );
  });

  it('logs TRAKT_CLIENT_ID hint on 403 for token exchange', async () => {
    const app = buildApp(mockFetch(403, { error: 'invalid_api_key' }));
    await request(app).post('/trakt/token').send({ code: 'test-code' });
    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining('TRAKT_CLIENT_ID'));
  });

  it('logs TRAKT_CLIENT_ID hint on 403 for token refresh', async () => {
    const app = buildApp(mockFetch(403, { error: 'invalid_api_key' }));
    await request(app).post('/trakt/token/refresh').send({ refresh_token: 'old-token' });
    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining('TRAKT_CLIENT_ID'));
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
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('HTTP 410'),
      expect.objectContaining({ status: 410 })
    );
    expect(errorSpy).not.toHaveBeenCalled();

    warnSpy.mockClear();
    errorSpy.mockClear();

    const app418 = buildApp(mockFetch(418, { error: 'denied' }));
    await request(app418).post('/trakt/token').send({ code: 'test-code' });
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('HTTP 418'),
      expect.objectContaining({ status: 418 })
    );
    expect(errorSpy).not.toHaveBeenCalled();

    warnSpy.mockRestore();
  });

  it('logs warn (not error) for HTTP 429 slow down', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const app = buildApp(mockFetch(429, { error: 'slow_down' }));
    await request(app).post('/trakt/token').send({ code: 'test-code' });
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('HTTP 429'),
      expect.objectContaining({ status: 429 })
    );
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
      expect.any(String)
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
      expect.any(String)
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
      /\[DEBUG\].*GET.*\/health.*200/.test(msg)
    );
    expect(healthLog).toBeDefined();
  });

  it('log line includes method, path, status, and timing', async () => {
    const app = buildApp(mockFetch(200, {}), { debug: true });
    await request(app).post('/trakt/token').send({ code: 'device-code-abc' });
    // The debug middleware logs request timing; logTraktCall also logs Trakt API details
    const requestLog = debugSpy.mock.calls.find(([msg]) =>
      /\[DEBUG\].*POST.*\/trakt\/token.*\d+ms/.test(msg)
    );
    expect(requestLog).toBeDefined();
  });

  it('logs debug line even when the endpoint returns an error status', async () => {
    const app = buildApp(mockFetch(400, { error: 'invalid_grant' }), { debug: true });
    await request(app).post('/trakt/token').send({ code: 'bad-code' });
    const requestLog = debugSpy.mock.calls.find(([msg]) => /\[DEBUG\].*400.*ms/.test(msg));
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
    const app = buildApp(
      mockFetch(200, {
        access_token: 'acc-123',
        refresh_token: 'ref-456',
        expires_in: 7776000,
        token_type: 'Bearer',
        scope: 'public',
      }),
      { debug: true }
    );

    await request(app).post('/trakt/token').send({ code: 'device-code-abc' });

    const allLogs = debugSpy.mock.calls.map((c) => c.join(' '));
    expect(
      allLogs.some((l) => l.includes('Token exchange') && l.includes('/oauth/device/token'))
    ).toBe(true);
    expect(allLogs.some((l) => l.includes('Token exchange') && l.includes('request body'))).toBe(
      true
    );
    expect(
      allLogs.some(
        (l) => l.includes('Token exchange') && l.includes('response status') && l.includes('200')
      )
    ).toBe(true);
    expect(allLogs.some((l) => l.includes('Token exchange') && l.includes('response body'))).toBe(
      true
    );
  });

  it('logs outgoing request details for token refresh', async () => {
    const app = buildApp(
      mockFetch(200, {
        access_token: 'new-acc-789',
        refresh_token: 'new-ref-012',
        expires_in: 7776000,
      }),
      { debug: true }
    );

    await request(app).post('/trakt/token/refresh').send({ refresh_token: 'old-ref-token' });

    const allLogs = debugSpy.mock.calls.map((c) => c.join(' '));
    expect(allLogs.some((l) => l.includes('Token refresh') && l.includes('/oauth/token'))).toBe(
      true
    );
    expect(allLogs.some((l) => l.includes('Token refresh') && l.includes('request body'))).toBe(
      true
    );
    expect(
      allLogs.some(
        (l) => l.includes('Token refresh') && l.includes('response status') && l.includes('200')
      )
    ).toBe(true);
  });

  it('masks client_secret in debug logs', async () => {
    const app = buildApp(
      mockFetch(200, {
        access_token: 'acc-123',
        refresh_token: 'ref-456',
        expires_in: 7776000,
        token_type: 'Bearer',
        scope: 'public',
      }),
      { debug: true }
    );

    await request(app).post('/trakt/token').send({ code: 'device-code-abc' });

    const allLogs = debugSpy.mock.calls.map((c) => c.join(' '));
    const bodyLog = allLogs.find((l) => l.includes('request body'));
    expect(bodyLog).toBeDefined();
    expect(bodyLog).not.toContain('test-client-secret');
    expect(bodyLog).toContain('test***');
  });

  it('masks access_token and refresh_token in response body debug logs', async () => {
    const app = buildApp(
      mockFetch(200, {
        access_token: 'acc-full-secret-token',
        refresh_token: 'ref-full-secret-token',
        expires_in: 7776000,
        token_type: 'Bearer',
        scope: 'public',
      }),
      { debug: true }
    );

    await request(app).post('/trakt/token').send({ code: 'device-code-abc' });

    const allLogs = debugSpy.mock.calls.map((c) => c.join(' '));
    const bodyLog = allLogs.find((l) => l.includes('response body'));
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
    const app = buildApp(
      mockFetch(
        200,
        {
          access_token: 'acc-123',
          refresh_token: 'ref-456',
          expires_in: 7776000,
          token_type: 'Bearer',
          scope: 'public',
        },
        headers
      ),
      { debug: true }
    );

    await request(app).post('/trakt/token').send({ code: 'device-code-abc' });

    const allLogs = debugSpy.mock.calls.map((c) => c.join(' '));
    const headerLog = allLogs.find((l) => l.includes('response headers'));
    expect(headerLog).toBeDefined();
    expect(headerLog).toContain('x-ratelimit-limit');
    expect(headerLog).toContain('1000');
  });

  it('logs response body for credential check when debug is enabled', async () => {
    const logSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
    const app = buildApp(
      mockFetch(200, {
        device_code: 'mock-device-code',
        user_code: 'ABCD1234',
        verification_url: 'https://trakt.tv/activate',
        expires_in: 600,
        interval: 5,
      }),
      { debug: true }
    );
    await app.verifyCredentials();
    logSpy.mockRestore();

    const allLogs = debugSpy.mock.calls.map((c) => c.join(' '));
    expect(allLogs.some((l) => l.includes('Credential check') && l.includes('response body'))).toBe(
      true
    );
  });

  it('does not log Trakt API call details when debug is false', async () => {
    const app = buildApp(
      mockFetch(200, {
        access_token: 'acc-123',
        refresh_token: 'ref-456',
        expires_in: 7776000,
        token_type: 'Bearer',
        scope: 'public',
      })
    );

    await request(app).post('/trakt/token').send({ code: 'device-code-abc' });

    const allLogs = debugSpy.mock.calls.map((c) => c.join(' '));
    expect(allLogs.some((l) => l.includes('Token exchange'))).toBe(false);
  });
});

// ── Production-mode safety ─────────────────────────────────────────────────

describe('Production-mode safety — no internal details in responses', () => {
  let errorSpy;

  beforeEach(() => {
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    errorSpy.mockRestore();
  });

  it('does not include a stack trace in the error response body', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app)
      .post('/trakt/token')
      .set('Content-Type', 'application/json')
      .set('Content-Encoding', 'utterly-invalid-encoding')
      .send('{"code":"abc"}');
    expect(res.status).toBe(500);
    const body = JSON.stringify(res.body);
    expect(body).not.toMatch(/at\s+\w+\s+\(.*:\d+:\d+\)/); // stack frame pattern
    expect(body).not.toMatch(/Error:/);
  });

  it('does not include Express internal error HTML in any response body', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app).get('/does-not-exist');
    expect(res.headers['content-type']).toMatch(/application\/json/);
    expect(res.text).not.toMatch(/<html/i);
  });

  it('global error handler returns JSON, not HTML, for unhandled errors', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app)
      .post('/trakt/token')
      .set('Content-Type', 'application/json')
      .set('Content-Encoding', 'utterly-invalid-encoding')
      .send('{"code":"abc"}');
    expect(res.headers['content-type']).toMatch(/application\/json/);
    expect(res.body).toEqual({ error: 'internal_error' });
  });
});

// ── filterTokenResponse (shared helper) ────────────────────────────────────

describe('filterTokenResponse — extra Trakt fields are stripped', () => {
  it('strips unexpected fields from token exchange response', async () => {
    const fetchFn = mockFetch(200, {
      access_token: 'acc-123',
      refresh_token: 'ref-456',
      expires_in: 7776000,
      token_type: 'Bearer',
      scope: 'public',
      user_id: 12345,
      created_at: 1700000000,
      username: 'alice',
    });
    const app = buildApp(fetchFn);

    const res = await request(app).post('/trakt/token').send({ code: 'device-code-abc' });

    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      access_token: 'acc-123',
      refresh_token: 'ref-456',
      expires_in: 7776000,
      token_type: 'Bearer',
      scope: 'public',
    });
    expect(res.body).not.toHaveProperty('user_id');
    expect(res.body).not.toHaveProperty('created_at');
    expect(res.body).not.toHaveProperty('username');
  });

  it('strips unexpected fields from token refresh response', async () => {
    const fetchFn = mockFetch(200, {
      access_token: 'new-acc-789',
      refresh_token: 'new-ref-012',
      expires_in: 7776000,
      token_type: 'Bearer',
      scope: 'public',
      user_id: 99,
      created_at: 1700000001,
    });
    const app = buildApp(fetchFn);

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
    expect(res.body).not.toHaveProperty('user_id');
    expect(res.body).not.toHaveProperty('created_at');
  });
});

// ── handleUpstreamError (shared catch handler) ─────────────────────────────

describe('handleUpstreamError — consistent error responses across both endpoints', () => {
  it('returns 504 on timeout for token exchange', async () => {
    const hangingFetch = vi.fn().mockImplementation(
      (_url, options) =>
        new Promise((_resolve, reject) => {
          options?.signal?.addEventListener('abort', () => {
            const err = new Error('aborted');
            err.name = 'AbortError';
            reject(err);
          });
        })
    );
    const app = buildApp(hangingFetch, { fetchTimeoutMs: 50 });
    const res = await request(app).post('/trakt/token').send({ code: 'x' });
    expect(res.status).toBe(504);
    expect(res.body).toEqual({ error: 'Upstream timeout' });
  });

  it('returns 504 on timeout for token refresh', async () => {
    const hangingFetch = vi.fn().mockImplementation(
      (_url, options) =>
        new Promise((_resolve, reject) => {
          options?.signal?.addEventListener('abort', () => {
            const err = new Error('aborted');
            err.name = 'AbortError';
            reject(err);
          });
        })
    );
    const app = buildApp(hangingFetch, { fetchTimeoutMs: 50 });
    const res = await request(app).post('/trakt/token/refresh').send({ refresh_token: 'x' });
    expect(res.status).toBe(504);
    expect(res.body).toEqual({ error: 'Upstream timeout' });
  });

  it('returns 502 on network error for token exchange', async () => {
    const app = buildApp(vi.fn().mockRejectedValue(new Error('ECONNREFUSED')));
    const res = await request(app).post('/trakt/token').send({ code: 'x' });
    expect(res.status).toBe(502);
    expect(res.body).toEqual({ error: 'Upstream error' });
  });

  it('returns 502 on network error for token refresh', async () => {
    const app = buildApp(vi.fn().mockRejectedValue(new Error('ECONNREFUSED')));
    const res = await request(app).post('/trakt/token/refresh').send({ refresh_token: 'x' });
    expect(res.status).toBe(502);
    expect(res.body).toEqual({ error: 'Upstream error' });
  });
});

// ── Trust proxy ────────────────────────────────────────────────────────────

describe('Trust proxy', () => {
  it('does not return 500 when X-Forwarded-For header is present', async () => {
    const app = buildApp(mockFetch(200, {}));
    // Without `app.set('trust proxy', 1)`, express-rate-limit throws
    // ERR_ERL_UNEXPECTED_X_FORWARDED_FOR and all requests fail with 500.
    const res = await request(app)
      .post('/trakt/token')
      .set('X-Forwarded-For', '1.2.3.4')
      .send({ code: 'device-code-abc' });
    expect(res.status).not.toBe(500);
  });

  it('responds normally with multiple X-Forwarded-For hops', async () => {
    const tokenBody = {
      access_token: 'acc',
      refresh_token: 'ref',
      expires_in: 7776000,
      token_type: 'Bearer',
      scope: 'public',
    };
    const app = buildApp(mockFetch(200, tokenBody));
    const res = await request(app)
      .post('/trakt/token')
      .set('X-Forwarded-For', '10.0.0.1, 172.16.0.1')
      .send({ code: 'valid-code' });
    expect(res.status).toBe(200);
    expect(res.body.access_token).toBe('acc');
  });
});

// ── filterErrorResponse — sanitize Trakt error bodies (#551) ───────────────

describe('filterErrorResponse — Trakt error bodies are sanitized before forwarding', () => {
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

  it('strips non-standard fields from a Trakt 4xx body on token exchange', async () => {
    const app = buildApp(
      mockFetch(403, {
        error: 'invalid_api_key',
        error_description: 'API key revoked',
        rate_limit_remaining: 5,
        account_id: 12345,
        access_token: 'leaked-token',
        debug_hint: 'internal-trakt-state',
      })
    );

    const res = await request(app).post('/trakt/token').send({ code: 'device-code-abc' });

    expect(res.status).toBe(403);
    expect(res.body).toEqual({
      error: 'invalid_api_key',
      error_description: 'API key revoked',
    });
    expect(res.body).not.toHaveProperty('rate_limit_remaining');
    expect(res.body).not.toHaveProperty('account_id');
    expect(res.body).not.toHaveProperty('access_token');
    expect(res.body).not.toHaveProperty('debug_hint');
  });

  it('strips non-standard fields from a Trakt 4xx body on token refresh', async () => {
    const app = buildApp(
      mockFetch(401, {
        error: 'invalid_token',
        error_description: 'expired',
        user_id: 99,
        partial_access_token: 'leak',
      })
    );

    const res = await request(app)
      .post('/trakt/token/refresh')
      .send({ refresh_token: 'expired-token' });

    expect(res.status).toBe(401);
    expect(res.body).toEqual({
      error: 'invalid_token',
      error_description: 'expired',
    });
    expect(res.body).not.toHaveProperty('user_id');
    expect(res.body).not.toHaveProperty('partial_access_token');
  });

  it('returns an empty body when the Trakt error has no error / error_description', async () => {
    const app = buildApp(mockFetch(500, { rate_limit_remaining: 0, internal_id: 'x' }));
    const res = await request(app).post('/trakt/token').send({ code: 'device-code-abc' });
    expect(res.status).toBe(500);
    expect(res.body).toEqual({});
  });

  it('drops non-string error / error_description fields', async () => {
    const app = buildApp(mockFetch(400, { error: 42, error_description: { nested: 'object' } }));
    const res = await request(app).post('/trakt/token').send({ code: 'device-code-abc' });
    expect(res.status).toBe(400);
    expect(res.body).toEqual({});
  });

  it('logs only traktErrorCode server-side, not non-standard Trakt response fields', async () => {
    const app = buildApp(
      mockFetch(403, {
        error: 'invalid_api_key',
        rate_limit_remaining: 5,
        account_id: 99999,
      })
    );
    await request(app).post('/trakt/token').send({ code: 'device-code-abc' });
    // The OAuth error code is still available for operators.
    expect(errorSpy).toHaveBeenCalledWith(
      expect.stringContaining('HTTP 403'),
      expect.objectContaining({ traktErrorCode: 'invalid_api_key' })
    );
    // Non-standard fields (rate_limit_remaining, account_id) must NOT appear in any log.
    const allLogs = errorSpy.mock.calls.map((c) => c.map(String).join(' '));
    expect(allLogs.every((l) => !l.includes('rate_limit_remaining'))).toBe(true);
    expect(allLogs.every((l) => !l.includes('account_id'))).toBe(true);
  });
});

// ── Body parser hardening (#552) ───────────────────────────────────────────

describe('Body parser hardening — content type and size limits', () => {
  it('rejects POST with non-JSON content-type with 415', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app)
      .post('/trakt/token')
      .set('Content-Type', 'text/plain')
      .send('code=abc');
    expect(res.status).toBe(415);
    expect(res.body).toEqual({ error: 'invalid_content_type' });
  });

  it('rejects POST with form-urlencoded content-type with 415', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app)
      .post('/trakt/token')
      .set('Content-Type', 'application/x-www-form-urlencoded')
      .send('code=abc');
    expect(res.status).toBe(415);
    expect(res.body).toEqual({ error: 'invalid_content_type' });
  });

  it('rejects oversized JSON body with 413 payload_too_large', async () => {
    const app = buildApp(mockFetch(200, {}));
    const oversize = JSON.stringify({ code: 'a'.repeat(8000) });
    const res = await request(app)
      .post('/trakt/token')
      .set('Content-Type', 'application/json')
      .send(oversize);
    expect(res.status).toBe(413);
    expect(res.body).toEqual({ error: 'payload_too_large' });
  });

  it('rejects malformed JSON with 400 invalid_json', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app)
      .post('/trakt/token')
      .set('Content-Type', 'application/json')
      .send('{not valid json');
    expect(res.status).toBe(400);
    expect(res.body).toEqual({ error: 'invalid_json' });
  });

  it('still accepts well-formed JSON under the 4kb limit', async () => {
    const app = buildApp(
      mockFetch(200, {
        access_token: 'acc',
        refresh_token: 'ref',
        expires_in: 7776000,
        token_type: 'Bearer',
        scope: 'public',
      })
    );
    const res = await request(app).post('/trakt/token').send({ code: 'device-code-abc' });
    expect(res.status).toBe(200);
    expect(res.body.access_token).toBe('acc');
  });

  it('does not 415 GET /health (only POST is gated)', async () => {
    const app = buildApp(mockFetch(200, {}));
    const res = await request(app).get('/health');
    expect(res.status).not.toBe(415);
  });
});

// ── Rate limiter — /health (#556) ──────────────────────────────────────────

describe('Rate limiter — /health endpoint (#556)', () => {
  it('allows up to 10 requests per minute to /health', async () => {
    const app = buildApp(mockFetch(200, {}));
    for (let i = 0; i < 10; i++) {
      const res = await request(app).get('/health');
      expect(res.status).not.toBe(429);
    }
  });

  it('returns 429 on the 11th /health request within a window', async () => {
    const app = buildApp(mockFetch(200, {}));
    for (let i = 0; i < 10; i++) {
      await request(app).get('/health');
    }
    const res = await request(app).get('/health');
    expect(res.status).toBe(429);
    expect(res.body).toEqual({ error: 'Too many requests, please try again later.' });
  });

  it('still applies the global rate limiter to /trakt routes', async () => {
    const app = buildApp(
      mockFetch(200, {
        access_token: 'acc',
        refresh_token: 'ref',
        expires_in: 7776000,
        token_type: 'Bearer',
        scope: 'public',
      })
    );
    // A single /trakt/token request should succeed (well within the 60/min global limit)
    const res = await request(app).post('/trakt/token').send({ code: 'device-code-abc' });
    expect(res.status).toBe(200);
  });

  it('/health rate limit response is JSON', async () => {
    const app = buildApp(mockFetch(200, {}));
    for (let i = 0; i < 11; i++) {
      await request(app).get('/health');
    }
    const res = await request(app).get('/health');
    expect(res.headers['content-type']).toMatch(/application\/json/);
  });
});

// ── Health response caching (#556) ─────────────────────────────────────────

describe('Health response caching (#556)', () => {
  it('returns cached response within TTL', async () => {
    // Build app with a short TTL and credential state that starts as "pending"
    const app = buildApp(mockFetch(200, {}), { healthCacheTtlMs: 5_000 });

    // First call — caches "starting / pending"
    const first = await request(app).get('/health');
    expect(first.status).toBe(503);
    expect(first.body.status).toBe('starting');

    // Verify credentials so in-memory state changes to "connected"
    await app.verifyCredentials();

    // Second call within TTL — should still return cached "starting"
    const second = await request(app).get('/health');
    expect(second.status).toBe(503);
    expect(second.body.status).toBe('starting');
  });

  it('returns fresh response after TTL expires', async () => {
    vi.useFakeTimers();
    try {
      const app = buildApp(mockFetch(200, {}), { healthCacheTtlMs: 5_000 });

      // First call — caches "starting"
      await request(app).get('/health');

      // Verify credentials so underlying state is now "connected"
      await app.verifyCredentials();

      // Advance past the 5s TTL
      vi.advanceTimersByTime(6_000);

      // Next call should return the fresh "connected" state
      const res = await request(app).get('/health');
      expect(res.status).toBe(200);
      expect(res.body.status).toBe('ok');
    } finally {
      vi.useRealTimers();
    }
  });

  it('does not cache when healthCacheTtlMs is 0', async () => {
    // buildApp defaults to healthCacheTtlMs: 0
    const app = buildApp(mockFetch(200, {}));

    // First call — pending state, not cached
    const first = await request(app).get('/health');
    expect(first.body.status).toBe('starting');

    await app.verifyCredentials();

    // Second call — should reflect updated state immediately
    const second = await request(app).get('/health');
    expect(second.status).toBe(200);
    expect(second.body.status).toBe('ok');
  });

  it('clearHealthCache exposes a way to invalidate the cache', async () => {
    const app = buildApp(mockFetch(200, {}), { healthCacheTtlMs: 60_000 });

    // Cache "starting" state
    await request(app).get('/health');
    await app.verifyCredentials();

    // Without clearing the cache, health still shows "starting"
    const stale = await request(app).get('/health');
    expect(stale.body.status).toBe('starting');

    // After clearing, health reflects the real state
    app.clearHealthCache();
    const fresh = await request(app).get('/health');
    expect(fresh.status).toBe(200);
    expect(fresh.body.status).toBe('ok');
  });
});

// ── Secure error logging (#558) ────────────────────────────────────────────
// Trakt error bodies can contain partial tokens, internal IDs, or rate-limit
// hints.  Production logs must never include raw body content — only the
// OAuth `error` code is emitted as a structured `traktErrorCode` field.

describe('Secure error logging — no raw Trakt response body in production logs (#558)', () => {
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

  it('does not log raw Trakt response body when token exchange fails (403)', async () => {
    const sensitiveBody = {
      error: 'invalid_api_key',
      access_token: 'super-secret-token',
      account_id: 12345,
      debug_hint: 'internal-trakt-state',
    };
    const app = buildApp(mockFetch(403, sensitiveBody));
    await request(app).post('/trakt/token').send({ code: 'test-code' });

    const allLogs = [
      ...errorSpy.mock.calls.map((c) => c.map(String).join(' ')),
      ...warnSpy.mock.calls.map((c) => c.map(String).join(' ')),
    ];
    expect(allLogs.every((l) => !l.includes('super-secret-token'))).toBe(true);
    expect(allLogs.every((l) => !l.includes('account_id'))).toBe(true);
    expect(allLogs.every((l) => !l.includes('debug_hint'))).toBe(true);
  });

  it('does not log raw Trakt response body when token refresh fails (401)', async () => {
    const sensitiveBody = {
      error: 'invalid_token',
      refresh_token: 'leaked-refresh-token',
      user_id: 99,
    };
    const app = buildApp(mockFetch(401, sensitiveBody));
    await request(app).post('/trakt/token/refresh').send({ refresh_token: 'old-token' });

    const allLogs = [
      ...errorSpy.mock.calls.map((c) => c.map(String).join(' ')),
      ...warnSpy.mock.calls.map((c) => c.map(String).join(' ')),
    ];
    expect(allLogs.every((l) => !l.includes('leaked-refresh-token'))).toBe(true);
    expect(allLogs.every((l) => !l.includes('user_id'))).toBe(true);
  });

  it('does not log raw Trakt response body during credential check failure', async () => {
    const sensitiveBody = {
      error: 'invalid_api_key',
      client_secret: 'leaked-secret',
      internal_id: 'trakt-internal-42',
    };
    const app = buildApp(mockFetch(403, sensitiveBody));
    await app.verifyCredentials();

    const allLogs = errorSpy.mock.calls.map((c) => c.map(String).join(' '));
    expect(allLogs.every((l) => !l.includes('leaked-secret'))).toBe(true);
    expect(allLogs.every((l) => !l.includes('internal_id'))).toBe(true);
  });

  it('still emits traktErrorCode in the structured context object for token exchange', async () => {
    const app = buildApp(mockFetch(500, { error: 'server_error' }));
    await request(app).post('/trakt/token').send({ code: 'test-code' });
    expect(errorSpy).toHaveBeenCalledWith(
      expect.stringContaining('HTTP 500'),
      expect.objectContaining({ status: 500, traktErrorCode: 'server_error' })
    );
  });

  it('still emits traktErrorCode in the structured context object for token refresh', async () => {
    const app = buildApp(mockFetch(401, { error: 'invalid_token' }));
    await request(app).post('/trakt/token/refresh').send({ refresh_token: 'old-token' });
    expect(errorSpy).toHaveBeenCalledWith(
      expect.stringContaining('HTTP 401'),
      expect.objectContaining({ status: 401, traktErrorCode: 'invalid_token' })
    );
  });

  it('emits traktErrorCode: undefined when Trakt body has no error field', async () => {
    const app = buildApp(mockFetch(500, { message: 'internal error', code: 42 }));
    await request(app).post('/trakt/token').send({ code: 'test-code' });
    expect(errorSpy).toHaveBeenCalledWith(
      expect.stringContaining('HTTP 500'),
      expect.objectContaining({ status: 500, traktErrorCode: undefined })
    );
  });

  it('does not log raw body for transient HTTP errors during credential check (503)', async () => {
    const sensitiveBody = {
      error: 'server_overloaded',
      rate_limit_remaining: 0,
      internal_msg: 'overload',
    };
    const app = buildApp(mockFetch(503, sensitiveBody));
    await app.verifyCredentials();
    app.clearRetryTimer();

    const allLogs = errorSpy.mock.calls.map((c) => c.map(String).join(' '));
    expect(allLogs.every((l) => !l.includes('internal_msg'))).toBe(true);
    expect(allLogs.every((l) => !l.includes('rate_limit_remaining'))).toBe(true);
  });
});
