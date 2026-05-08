/**
 * WatchBuddy — Trakt Token Proxy (Express app)
 *
 * Creates and configures the Express application.
 * Separated from index.js so the app can be imported for testing.
 */

import { timingSafeEqual } from 'crypto';
import express from 'express';
import helmet from 'helmet';
import rateLimit from 'express-rate-limit';
import { createProviderCatalogRouter } from './routes/provider-catalog.js';

const TOKEN_PATTERN = /^[a-zA-Z0-9_-]+$/;
const MAX_TOKEN_LENGTH = 256;

/**
 * Validates a token-like string field.
 * @param {*} value - The value to validate
 * @param {string} fieldName - Human-readable field name for error messages
 * @returns {string|null} Error message, or null if valid
 */
function validateField(value, fieldName) {
  if (!value) return `Missing ${fieldName}`;
  if (typeof value !== 'string') return `${fieldName} must be a string`;
  if (value.length > MAX_TOKEN_LENGTH) return `${fieldName} exceeds max length`;
  if (!TOKEN_PATTERN.test(value)) return `${fieldName} contains invalid characters`;
  return null;
}

const SECRET_KEYS = ['client_secret', 'refresh_token', 'access_token'];

/**
 * Returns a shallow copy of `obj` with sensitive values masked (first 4 chars + "***").
 */
function maskSecrets(obj) {
  if (!obj || typeof obj !== 'object') return obj;
  const masked = { ...obj };
  for (const key of SECRET_KEYS) {
    if (masked[key] && typeof masked[key] === 'string') {
      masked[key] = masked[key].slice(0, 4) + '***';
    }
  }
  return masked;
}

/**
 * Creates a configured Express app for the Trakt token proxy.
 *
 * @param {object} config
 * @param {string} config.clientId   - Trakt client ID
 * @param {string} config.clientSecret - Trakt client secret
 * @param {string} [config.traktApi]   - Trakt API base URL (default: https://api.trakt.tv)
 * @param {Function} [config.fetchFn]  - fetch implementation (default: global fetch)
 * @param {number} [config.fetchTimeoutMs] - Upstream fetch timeout in ms (default: 8000).
 *   Trakt token exchanges typically complete in < 2 s; 8 s leaves a generous margin while
 *   limiting how long a slow-loris connection can pin a socket. Override via FETCH_TIMEOUT_MS.
 * @param {boolean} [config.debug]     - Enable request debug logging (default: false)
 * @param {number} [config.healthCacheTtlMs] - TTL for /health response cache in ms (default: 30000, 0 = disabled)
 * @param {string} [config.healthToken] - Secret token for GET /health/detailed (X-Health-Token header). When omitted, /health/detailed returns 404.
 * @returns {import('express').Express}
 */
export function createApp(config) {
  const {
    clientId,
    clientSecret,
    traktApi = 'https://api.trakt.tv',
    fetchFn = fetch,
    fetchTimeoutMs = 8_000,
    version = '0.0.0',
    debug = false,
    healthCacheTtlMs = 30_000,
    healthToken,
  } = config;

  const traktHeaders = {
    'Content-Type': 'application/json',
    'trakt-api-key': clientId,
    'trakt-api-version': '2',
    'User-Agent': `WatchBuddy/${version}`,
  };

  async function fetchWithTimeout(url, options) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), fetchTimeoutMs);
    try {
      return await fetchFn(url, { ...options, signal: controller.signal });
    } finally {
      clearTimeout(timer);
    }
  }

  /**
   * Calls a Trakt token endpoint, returning the parsed response.
   *
   * Returns one of:
   *   { traktRes, data }         — JSON parsed successfully (any HTTP status)
   *   { pending: true }          — 400 with empty/non-JSON body during device-flow polling
   *                                (only when allowPending is true)
   *   { nonJsonStatus: number }  — non-JSON body on any other status
   *
   * Throws on network / timeout errors so the caller's catch block handles them.
   */
  async function callTraktToken(url, options, label, { allowPending = false } = {}) {
    const traktRes = await fetchWithTimeout(url, options);
    const rawText = await traktRes.text();
    let data;
    try {
      data = JSON.parse(rawText);
    } catch (_parseErr) {
      if (allowPending && traktRes.status === 400) {
        logTraktCall(`${label} (pending)`, url, options, traktRes);
        return { pending: true };
      }
      console.error(`${label}: Trakt returned non-JSON response (HTTP ${traktRes.status})`);
      logTraktCall(`${label} (non-JSON)`, url, options, traktRes);
      return { nonJsonStatus: traktRes.status };
    }
    logTraktCall(label, url, options, traktRes, data);
    return { traktRes, data };
  }

  /** Returns only the token fields the Android client needs — never echoes secrets. */
  function filterTokenResponse(data) {
    return {
      access_token: data.access_token,
      refresh_token: data.refresh_token,
      expires_in: data.expires_in,
      token_type: data.token_type,
      scope: data.scope,
    };
  }

  // Trakt error bodies can include rate-limit hints, account state, or partial
  // token fields — strip everything except the standard OAuth `error` /
  // `error_description` so the client never sees information it doesn't need.
  function filterErrorResponse(data) {
    if (!data || typeof data !== 'object') return {};
    const out = {};
    if (typeof data.error === 'string') out.error = data.error;
    if (typeof data.error_description === 'string') out.error_description = data.error_description;
    return out;
  }

  // Returns the OAuth `error` code from a Trakt response body, or undefined
  // when absent or not a string.  Used to build safe structured log context
  // without leaking raw body content to log aggregators.
  function extractTraktErrorCode(data) {
    return data && typeof data.error === 'string' ? data.error : undefined;
  }

  /**
   * Handles a caught fetch error (AbortError / network error) and writes the
   * appropriate HTTP error response.  fetchTimeoutMs is read from the outer closure.
   */
  function handleUpstreamError(label, err, res) {
    if (err.name === 'AbortError') {
      console.error(`${label}: upstream timeout after`, fetchTimeoutMs, 'ms');
      return res.status(504).json({ error: 'Upstream timeout' });
    }
    const category = err.code ? `network error (${err.code})` : `unexpected error (${err.name})`;
    console.error(`${label}: ${category}:`, err.message);
    return res.status(502).json({ error: 'Upstream error' });
  }

  /**
   * Logs full request/response details for a Trakt API call when debug mode is on.
   */
  function logTraktCall(label, url, options, traktRes, data) {
    if (!debug) return;

    const outgoingBody = options.body ? maskSecrets(JSON.parse(options.body)) : undefined;

    const responseHeaders = {};
    if (traktRes.headers?.forEach) {
      traktRes.headers.forEach((value, key) => {
        responseHeaders[key] = value;
      });
    }

    console.debug(`[DEBUG] ${label} → ${options.method} ${url}`);
    console.debug(`[DEBUG] ${label} request headers:`, JSON.stringify(options.headers));
    if (outgoingBody) {
      console.debug(`[DEBUG] ${label} request body:`, JSON.stringify(outgoingBody));
    }
    console.debug(`[DEBUG] ${label} response status: ${traktRes.status}`);
    console.debug(`[DEBUG] ${label} response headers:`, JSON.stringify(responseHeaders));
    if (data !== undefined) {
      const maskedData = maskSecrets(data);
      const snippet = JSON.stringify(maskedData).slice(0, 500);
      console.debug(`[DEBUG] ${label} response body:`, snippet);
    }
  }

  // Credential verification state
  let traktStatus = 'pending';
  let traktError = null;
  let credentialsVerified = false;
  let retryTimer = null;

  // True when we know for certain the proxy cannot exchange tokens —
  // e.g. the client secret is missing or the client ID was rejected by Trakt.
  // In this state /trakt/token returns 503 server_misconfigured so the Android
  // client can show a "contact the maintainer" message instead of "wrong credentials".
  let serverMisconfigured = !clientSecret;
  if (serverMisconfigured) {
    console.error(
      'TRAKT_CLIENT_SECRET is missing — token exchange will be rejected with 503 server_misconfigured.'
    );
  }

  // Retry delays: 5s, 15s, 30s, 60s, then stay at 60s
  const RETRY_DELAYS = [5_000, 15_000, 30_000, 60_000];

  function scheduleRetry(attempt) {
    const delay = RETRY_DELAYS[Math.min(attempt, RETRY_DELAYS.length - 1)];
    console.log(
      `Scheduling credential re-verification in ${delay / 1000}s (attempt ${attempt + 1})…`
    );
    retryTimer = setTimeout(() => verifyCredentials(attempt + 1), delay);
  }

  async function verifyCredentials(attempt = 0) {
    if (retryTimer) {
      clearTimeout(retryTimer);
      retryTimer = null;
    }
    const url = `${traktApi}/oauth/device/code`;
    const options = {
      method: 'POST',
      headers: traktHeaders,
      body: JSON.stringify({ client_id: clientId }),
    };
    try {
      const res = await fetchWithTimeout(url, options);

      let data;
      try {
        data = await res.json();
      } catch (_parseErr) {
        data = undefined;
      }

      logTraktCall('Credential check', url, options, res, data);

      if (res.ok) {
        traktStatus = 'connected';
        traktError = null;
        credentialsVerified = true;
        console.log('Trakt credential verification: OK');
      } else if (res.status === 401 || res.status === 403) {
        traktStatus = 'invalid_client_id';
        traktError = `Trakt returned ${res.status} — check that TRAKT_CLIENT_ID is correct.`;
        credentialsVerified = false;
        console.error(
          `Trakt credential verification failed: HTTP ${res.status} — TRAKT_CLIENT_ID may be invalid.`,
          { status: res.status, traktErrorCode: extractTraktErrorCode(data) }
        );
        serverMisconfigured = true;
        // Do not retry on 401/403 — credentials are definitively wrong
      } else {
        traktStatus = `trakt_http_${res.status}`;
        traktError = `Trakt returned HTTP ${res.status} during credential check`;
        credentialsVerified = false;
        console.error(`Trakt credential verification failed: HTTP ${res.status}`, {
          status: res.status,
          traktErrorCode: extractTraktErrorCode(data),
        });
        scheduleRetry(attempt);
      }
    } catch (err) {
      if (err.name === 'AbortError') {
        traktStatus = 'timeout';
        traktError = 'Trakt API did not respond within timeout';
        credentialsVerified = false;
        console.error('Trakt credential verification failed: timeout');
      } else {
        traktStatus = 'network_error';
        traktError = err.message;
        credentialsVerified = false;
        console.error('Trakt credential verification failed: network error:', err.message);
      }
      scheduleRetry(attempt);
    }
  }

  const app = express();
  // Trust the first proxy hop so express-rate-limit reads the real client IP
  // from X-Forwarded-For instead of treating all traffic as one bucket.
  app.set('trust proxy', 1);
  app.use(helmet());

  app.use((req, res, next) => {
    if (req.method === 'POST' && !req.is('application/json')) {
      return res.status(415).json({ error: 'invalid_content_type' });
    }
    next();
  });
  app.use(express.json({ limit: '4kb', strict: true }));
  app.use((err, _req, res, next) => {
    if (err?.type === 'entity.too.large') {
      return res.status(413).json({ error: 'payload_too_large' });
    }
    if (err?.type === 'entity.parse.failed') {
      return res.status(400).json({ error: 'invalid_json' });
    }
    return next(err);
  });

  if (debug) {
    app.use((req, res, next) => {
      const start = Date.now();
      const ip = req.ip ?? req.socket?.remoteAddress ?? 'unknown';
      res.on('finish', () => {
        const ms = Date.now() - start;
        console.debug(
          `[DEBUG] ${new Date().toISOString()} ${req.method} ${req.path} from ${ip} \u2192 ${res.statusCode} (${ms}ms)`
        );
      });
      next();
    });
  }

  // Rate limiting — Trakt allows 1000 calls/5min per app; apply globally
  const limiter = rateLimit({
    windowMs: 60 * 1000, // 1 minute
    limit: 60, // 60 requests per minute per IP
    message: { error: 'Too many requests, please try again later.' },
  });
  app.use(limiter);

  // Stricter limit for /health — 10 requests per minute per IP
  const healthLimiter = rateLimit({
    windowMs: 60 * 1000,
    limit: 10,
    message: { error: 'Too many requests, please try again later.' },
  });

  // Cache state for /health and /health/detailed — prevents rapid re-evaluation and amplification
  let healthCache = null; // { body: object, status: number, expiresAt: number }

  function computeHealthState() {
    if (serverMisconfigured && traktStatus === 'pending') {
      return {
        status: 503,
        body: {
          status: 'misconfigured',
          trakt: 'missing_client_secret',
          error: 'TRAKT_CLIENT_SECRET is not set — token exchange is disabled.',
        },
      };
    }
    if (traktStatus === 'pending') {
      return { status: 503, body: { status: 'starting', trakt: 'pending' } };
    }
    if (credentialsVerified) {
      return {
        status: 200,
        body: { status: 'ok', trakt: 'connected', validated: 'client_id_via_oauth' },
      };
    }
    return { status: 503, body: { status: 'unhealthy', trakt: traktStatus, error: traktError } };
  }

  function safeTokenCompare(expected, provided) {
    try {
      return timingSafeEqual(Buffer.from(expected), Buffer.from(provided));
    } catch {
      return false;
    }
  }

  // ── GET /provider-catalog ───────────────────────────────────────────────────
  // Public read-only endpoint — no auth required.
  app.use(createProviderCatalogRouter());

  // ── POST /trakt/token ───────────────────────────────────────────────────────
  // Body: { "code": "<device_code>" }
  // Calls Trakt /oauth/device/token with server-side secret injected
  app.post('/trakt/token', async (req, res) => {
    if (serverMisconfigured) {
      console.error(
        'Token exchange blocked: proxy is misconfigured (missing or rejected credentials).'
      );
      return res.status(503).json({ error: 'server_misconfigured' });
    }

    const { code } = req.body;
    const codeError = validateField(code, 'code');
    if (codeError) return res.status(400).json({ error: codeError });

    const url = `${traktApi}/oauth/device/token`;
    const options = {
      method: 'POST',
      headers: traktHeaders,
      body: JSON.stringify({ code, client_id: clientId, client_secret: clientSecret }),
    };

    try {
      const result = await callTraktToken(url, options, 'Token exchange', { allowPending: true });

      // Trakt uses empty / non-JSON 400 responses during device-flow polling
      // to mean "user hasn't authorized yet — keep polling". Pass it through
      // as 400 so the client's polling loop treats it as pending, not a
      // 5xx error that burns its consecutive-failure budget.
      if (result.pending) return res.status(400).json({ error: 'authorization_pending' });

      if (result.nonJsonStatus !== undefined) {
        return res.status(502).json({
          error: `Upstream returned non-JSON response (HTTP ${result.nonJsonStatus})`,
        });
      }

      const { traktRes, data } = result;
      if (!traktRes.ok) {
        if (traktRes.status === 400) {
          // Expected during device flow polling — user hasn't authorized yet
        } else if ([409, 410, 418, 429].includes(traktRes.status)) {
          console.warn(`Token exchange: Trakt returned HTTP ${traktRes.status}`, {
            status: traktRes.status,
            traktErrorCode: extractTraktErrorCode(data),
          });
        } else {
          console.error(`Token exchange: Trakt returned HTTP ${traktRes.status}`, {
            status: traktRes.status,
            traktErrorCode: extractTraktErrorCode(data),
          });
          if (traktRes.status === 403) {
            console.error(
              'Hint: HTTP 403 from Trakt usually means TRAKT_CLIENT_ID is invalid or revoked.'
            );
          }
        }
        return res.status(traktRes.status).json(filterErrorResponse(data));
      }

      return res.json(filterTokenResponse(data));
    } catch (err) {
      return handleUpstreamError('Token exchange', err, res);
    }
  });

  // ── POST /trakt/token/refresh ───────────────────────────────────────────────
  // Body: { "refresh_token": "<token>" }
  app.post('/trakt/token/refresh', async (req, res) => {
    const { refresh_token } = req.body;
    const rtError = validateField(refresh_token, 'refresh_token');
    if (rtError) return res.status(400).json({ error: rtError });

    const url = `${traktApi}/oauth/token`;
    const options = {
      method: 'POST',
      headers: traktHeaders,
      body: JSON.stringify({
        refresh_token,
        client_id: clientId,
        client_secret: clientSecret,
        redirect_uri: 'urn:ietf:wg:oauth:2.0:oob',
        grant_type: 'refresh_token',
      }),
    };

    try {
      const result = await callTraktToken(url, options, 'Token refresh');

      if (result.nonJsonStatus !== undefined) {
        return res.status(502).json({
          error: `Upstream returned non-JSON response (HTTP ${result.nonJsonStatus})`,
        });
      }

      const { traktRes, data } = result;
      if (!traktRes.ok) {
        console.error(`Token refresh: Trakt returned HTTP ${traktRes.status}`, {
          status: traktRes.status,
          traktErrorCode: extractTraktErrorCode(data),
        });
        if (traktRes.status === 403) {
          console.error(
            'Hint: HTTP 403 from Trakt usually means TRAKT_CLIENT_ID is invalid or revoked.'
          );
        }
        return res.status(traktRes.status).json(filterErrorResponse(data));
      }

      return res.json(filterTokenResponse(data));
    } catch (err) {
      return handleUpstreamError('Token refresh', err, res);
    }
  });

  // ── GET /health ─────────────────────────────────────────────────────────────
  // Unauthenticated liveness check. Returns only { status: 'ok' | 'unhealthy' }
  // so operational state is never exposed to unauthenticated callers.
  app.get('/health', healthLimiter, (_req, res) => {
    const now = Date.now();
    if (healthCache && healthCache.expiresAt > now) {
      const isOk = healthCache.status === 200;
      return res.status(healthCache.status).json({ status: isOk ? 'ok' : 'unhealthy' });
    }

    const { status, body } = computeHealthState();

    if (healthCacheTtlMs > 0) {
      healthCache = { body, status, expiresAt: now + healthCacheTtlMs };
    }
    const isOk = status === 200;
    return res.status(status).json({ status: isOk ? 'ok' : 'unhealthy' });
  });

  // ── GET /health/detailed ─────────────────────────────────────────────────────
  // Authenticated verbose health check. Requires the X-Health-Token header to
  // match the HEALTH_TOKEN env var. Returns the full diagnostic payload.
  // Returns 404 when HEALTH_TOKEN is not configured so the endpoint stays dark.
  app.get('/health/detailed', healthLimiter, (req, res) => {
    if (!healthToken) {
      return res.status(404).json({ error: 'not_found' });
    }

    const provided = req.headers['x-health-token'];
    if (!provided || !safeTokenCompare(healthToken, provided)) {
      return res.status(401).json({ error: 'unauthorized' });
    }

    const now = Date.now();
    if (healthCache && healthCache.expiresAt > now) {
      return res.status(healthCache.status).json(healthCache.body);
    }

    const { status, body } = computeHealthState();

    if (healthCacheTtlMs > 0) {
      healthCache = { body, status, expiresAt: now + healthCacheTtlMs };
    }
    return res.status(status).json(body);
  });

  // Catch-all 404 — keeps unknown paths off Express's default HTML response.
  app.use((_req, res) => {
    res.status(404).json({ error: 'not_found' });
  });

  // Global error handler — final safety net so unhandled exceptions never
  // leak stack traces via Express's default error response.
  app.use((err, _req, res, _next) => {
    console.error('Unhandled error:', err);
    res.status(500).json({ error: 'internal_error' });
  });

  app.verifyCredentials = verifyCredentials;
  app.clearRetryTimer = () => {
    if (retryTimer) clearTimeout(retryTimer);
  };
  app.clearHealthCache = () => {
    healthCache = null;
  };
  app.fetchTimeoutMs = fetchTimeoutMs;

  return app;
}
