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
    debug = false,
  } = config;

  async function fetchWithTimeout(url, options) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), fetchTimeoutMs);
    try {
      return await fetchFn(url, { ...options, signal: controller.signal });
    } finally {
      clearTimeout(timer);
    }
  }

  const app = express();
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
    const { code } = req.body;
    const codeError = validateField(code, 'code');
    if (codeError) return res.status(400).json({ error: codeError });

    try {
      const traktRes = await fetchWithTimeout(`${traktApi}/oauth/device/token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          code,
          client_id: clientId,
          client_secret: clientSecret,
        }),
      });

      let data;
      try {
        data = await traktRes.json();
      } catch (_parseErr) {
        console.error(`Token exchange: Trakt returned non-JSON response (HTTP ${traktRes.status})`);
        return res.status(502).json({
          error: `Upstream returned non-JSON response (HTTP ${traktRes.status})`,
        });
      }

      if (!traktRes.ok) {
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
        console.error('Token exchange timeout');
        return res.status(504).json({ error: 'Upstream timeout' });
      }
      console.error('Token exchange error:', err);
      return res.status(502).json({ error: 'Upstream error' });
    }
  });

  // ── POST /trakt/token/refresh ───────────────────────────────────────────────
  // Body: { "refresh_token": "<token>" }
  app.post('/trakt/token/refresh', async (req, res) => {
    const { refresh_token } = req.body;
    const rtError = validateField(refresh_token, 'refresh_token');
    if (rtError) return res.status(400).json({ error: rtError });

    try {
      const traktRes = await fetchWithTimeout(`${traktApi}/oauth/token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          refresh_token,
          client_id: clientId,
          client_secret: clientSecret,
          grant_type: 'refresh_token',
        }),
      });

      let data;
      try {
        data = await traktRes.json();
      } catch (_parseErr) {
        console.error(`Token refresh: Trakt returned non-JSON response (HTTP ${traktRes.status})`);
        return res.status(502).json({
          error: `Upstream returned non-JSON response (HTTP ${traktRes.status})`,
        });
      }
      if (!traktRes.ok) return res.status(traktRes.status).json(data);

      return res.json({
        access_token: data.access_token,
        refresh_token: data.refresh_token,
        expires_in: data.expires_in,
      });
    } catch (err) {
      if (err.name === 'AbortError') {
        console.error('Token refresh timeout');
        return res.status(504).json({ error: 'Upstream timeout' });
      }
      console.error('Token refresh error:', err);
      return res.status(502).json({ error: 'Upstream error' });
    }
  });

  // ── GET /health ─────────────────────────────────────────────────────────────
  app.get('/health', (_req, res) => res.json({ status: 'ok' }));

  return app;
}
