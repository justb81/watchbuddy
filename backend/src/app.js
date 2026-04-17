/**
 * WatchBuddy — Trakt Token Proxy (Express app)
 *
 * Creates and configures the Express application.
 * Separated from index.js so the app can be imported for testing.
 */

import express from 'express';
import helmet from 'helmet';
import rateLimit from 'express-rate-limit';

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
 * @param {number} [config.fetchTimeoutMs] - Upstream fetch timeout in ms (default: 15000)
 * @param {boolean} [config.debug]     - Enable request debug logging (default: false)
 * @returns {import('express').Express}
 */
export function createApp(config) {
  const {
    clientId,
    clientSecret,
    traktApi = 'https://api.trakt.tv',
    fetchFn = fetch,
    fetchTimeoutMs = 15_000,
    version = '0.0.0',
    debug = false,
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
   * Logs full request/response details for a Trakt API call when debug mode is on.
   */
  function logTraktCall(label, url, options, traktRes, data) {
    if (!debug) return;

    const outgoingBody = options.body
      ? maskSecrets(JSON.parse(options.body))
      : undefined;

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
      'TRAKT_CLIENT_SECRET is missing — token exchange will be rejected with 503 server_misconfigured.',
    );
  }

  // Retry delays: 5s, 15s, 30s, 60s, then stay at 60s
  const RETRY_DELAYS = [5_000, 15_000, 30_000, 60_000];

  function scheduleRetry(attempt) {
    const delay = RETRY_DELAYS[Math.min(attempt, RETRY_DELAYS.length - 1)];
    console.log(`Scheduling credential re-verification in ${delay / 1000}s (attempt ${attempt + 1})…`);
    retryTimer = setTimeout(() => verifyCredentials(attempt + 1), delay);
  }

  async function verifyCredentials(attempt = 0) {
    if (retryTimer) { clearTimeout(retryTimer); retryTimer = null; }
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
        if (data !== undefined) {
          const bodySnippet = JSON.stringify(data).slice(0, 200);
          console.error(`Credential check: Trakt response body: ${bodySnippet}`);
        }
        console.error(`Trakt credential verification failed: HTTP ${res.status} — TRAKT_CLIENT_ID may be invalid.`);
        serverMisconfigured = true;
        // Do not retry on 401/403 — credentials are definitively wrong
      } else {
        traktStatus = `trakt_http_${res.status}`;
        traktError = `Trakt returned HTTP ${res.status} during credential check`;
        credentialsVerified = false;
        if (data !== undefined) {
          const bodySnippet = JSON.stringify(data).slice(0, 200);
          console.error(`Credential check: Trakt response body: ${bodySnippet}`);
        }
        console.error(`Trakt credential verification failed: HTTP ${res.status}`);
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
  app.use(express.json());

  if (debug) {
    app.use((req, res, next) => {
      const start = Date.now();
      const ip = req.ip ?? req.socket?.remoteAddress ?? 'unknown';
      res.on('finish', () => {
        const ms = Date.now() - start;
        console.debug(
          `[DEBUG] ${new Date().toISOString()} ${req.method} ${req.path} from ${ip} \u2192 ${res.statusCode} (${ms}ms)`,
        );
      });
      next();
    });
  }

  // Rate limiting — Trakt allows 1000 calls/5min per app
  const limiter = rateLimit({
    windowMs: 60 * 1000, // 1 minute
    max: 60,             // 60 requests per minute per IP
    message: { error: 'Too many requests, please try again later.' },
  });
  app.use('/trakt', limiter);

  // ── POST /trakt/token ───────────────────────────────────────────────────────
  // Body: { "code": "<device_code>" }
  // Calls Trakt /oauth/device/token with server-side secret injected
  app.post('/trakt/token', async (req, res) => {
    if (serverMisconfigured) {
      console.error('Token exchange blocked: proxy is misconfigured (missing or rejected credentials).');
      return res.status(503).json({ error: 'server_misconfigured' });
    }

    const { code } = req.body;
    const codeError = validateField(code, 'code');
    if (codeError) return res.status(400).json({ error: codeError });

    const url = `${traktApi}/oauth/device/token`;
    const options = {
      method: 'POST',
      headers: traktHeaders,
      body: JSON.stringify({
        code,
        client_id: clientId,
        client_secret: clientSecret,
      }),
    };

    try {
      const traktRes = await fetchWithTimeout(url, options);
      const rawText = await traktRes.text();

      let data;
      try {
        data = JSON.parse(rawText);
      } catch (_parseErr) {
        // Trakt uses empty / non-JSON 400 responses during device-flow polling
        // to mean "user hasn't authorized yet — keep polling". Pass it through
        // as 400 so the client's polling loop treats it as pending, not a
        // 5xx error that burns its consecutive-failure budget.
        if (traktRes.status === 400) {
          logTraktCall('Token exchange (pending)', url, options, traktRes);
          return res.status(400).json({ error: 'authorization_pending' });
        }
        console.error(`Token exchange: Trakt returned non-JSON response (HTTP ${traktRes.status})`);
        logTraktCall('Token exchange (non-JSON)', url, options, traktRes);
        return res.status(502).json({
          error: `Upstream returned non-JSON response (HTTP ${traktRes.status})`,
        });
      }

      logTraktCall('Token exchange', url, options, traktRes, data);

      if (!traktRes.ok) {
        const bodySnippet = JSON.stringify(data).slice(0, 200);
        if (traktRes.status === 400) {
          // Expected during device flow polling — user hasn't authorized yet
        } else if ([409, 410, 418, 429].includes(traktRes.status)) {
          console.warn(`Token exchange: Trakt returned HTTP ${traktRes.status}: ${bodySnippet}`);
        } else {
          console.error(`Token exchange: Trakt returned HTTP ${traktRes.status}: ${bodySnippet}`);
          if (traktRes.status === 403) {
            console.error('Hint: HTTP 403 from Trakt usually means TRAKT_CLIENT_ID is invalid or revoked.');
          }
        }
        return res.status(traktRes.status).json(data);
      }

      // Return only what the client needs — never echo back the secret
      return res.json({
        access_token: data.access_token,
        refresh_token: data.refresh_token,
        expires_in: data.expires_in,
        token_type: data.token_type,
        scope: data.scope,
      });
    } catch (err) {
      if (err.name === 'AbortError') {
        console.error('Token exchange: upstream timeout after', fetchTimeoutMs, 'ms');
        return res.status(504).json({ error: 'Upstream timeout' });
      }
      const category = err.code
        ? `network error (${err.code})`
        : `unexpected error (${err.name})`;
      console.error(`Token exchange: ${category}:`, err.message);
      return res.status(502).json({ error: 'Upstream error' });
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
      const traktRes = await fetchWithTimeout(url, options);
      const rawText = await traktRes.text();

      let data;
      try {
        data = JSON.parse(rawText);
      } catch (_parseErr) {
        console.error(`Token refresh: Trakt returned non-JSON response (HTTP ${traktRes.status})`);
        logTraktCall('Token refresh (non-JSON)', url, options, traktRes);
        return res.status(502).json({
          error: `Upstream returned non-JSON response (HTTP ${traktRes.status})`,
        });
      }

      logTraktCall('Token refresh', url, options, traktRes, data);

      if (!traktRes.ok) {
        const bodySnippet = JSON.stringify(data).slice(0, 200);
        console.error(`Token refresh: Trakt returned HTTP ${traktRes.status}: ${bodySnippet}`);
        if (traktRes.status === 403) {
          console.error('Hint: HTTP 403 from Trakt usually means TRAKT_CLIENT_ID is invalid or revoked.');
        }
        return res.status(traktRes.status).json(data);
      }

      return res.json({
        access_token: data.access_token,
        refresh_token: data.refresh_token,
        expires_in: data.expires_in,
        token_type: data.token_type,
        scope: data.scope,
      });
    } catch (err) {
      if (err.name === 'AbortError') {
        console.error('Token refresh: upstream timeout after', fetchTimeoutMs, 'ms');
        return res.status(504).json({ error: 'Upstream timeout' });
      }
      const category = err.code
        ? `network error (${err.code})`
        : `unexpected error (${err.name})`;
      console.error(`Token refresh: ${category}:`, err.message);
      return res.status(502).json({ error: 'Upstream error' });
    }
  });

  // ── GET /health ─────────────────────────────────────────────────────────────
  app.get('/health', (_req, res) => {
    if (serverMisconfigured && traktStatus === 'pending') {
      return res.status(503).json({
        status: 'misconfigured',
        trakt: 'missing_client_secret',
        error: 'TRAKT_CLIENT_SECRET is not set — token exchange is disabled.',
      });
    }
    if (traktStatus === 'pending') {
      return res.status(503).json({ status: 'starting', trakt: 'pending' });
    }
    if (credentialsVerified) {
      return res.json({ status: 'ok', trakt: 'connected', validated: 'client_id_via_oauth' });
    }
    return res.status(503).json({
      status: 'unhealthy',
      trakt: traktStatus,
      error: traktError,
    });
  });

  app.verifyCredentials = verifyCredentials;
  app.clearRetryTimer = () => { if (retryTimer) clearTimeout(retryTimer); };

  return app;
}
