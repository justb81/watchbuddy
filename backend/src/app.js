/**
 * WatchBuddy — Trakt Token Proxy (Express app)
 *
 * Creates and configures the Express application.
 * Separated from index.js so the app can be imported for testing.
 */

import express from 'express';
import fetch from 'node-fetch';
import rateLimit from 'express-rate-limit';

/**
 * Creates a configured Express app for the Trakt token proxy.
 *
 * @param {object} config
 * @param {string} config.clientId   - Trakt client ID
 * @param {string} config.clientSecret - Trakt client secret
 * @param {string} [config.traktApi]   - Trakt API base URL (default: https://api.trakt.tv)
 * @param {Function} [config.fetchFn]  - fetch implementation (default: node-fetch)
 * @returns {import('express').Express}
 */
export function createApp(config) {
  const {
    clientId,
    clientSecret,
    traktApi = 'https://api.trakt.tv',
    fetchFn = fetch,
  } = config;

  const app = express();
  app.use(express.json());

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
    if (!code) return res.status(400).json({ error: 'Missing code' });

    try {
      const traktRes = await fetchFn(`${traktApi}/oauth/device/token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          code,
          client_id: clientId,
          client_secret: clientSecret,
        }),
      });

      const data = await traktRes.json();

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
      console.error('Token exchange error:', err);
      return res.status(502).json({ error: 'Upstream error' });
    }
  });

  // ── POST /trakt/token/refresh ───────────────────────────────────────────────
  // Body: { "refresh_token": "<token>" }
  app.post('/trakt/token/refresh', async (req, res) => {
    const { refresh_token } = req.body;
    if (!refresh_token) return res.status(400).json({ error: 'Missing refresh_token' });

    try {
      const traktRes = await fetchFn(`${traktApi}/oauth/token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          refresh_token,
          client_id: clientId,
          client_secret: clientSecret,
          grant_type: 'refresh_token',
        }),
      });

      const data = await traktRes.json();
      if (!traktRes.ok) return res.status(traktRes.status).json(data);

      return res.json({
        access_token: data.access_token,
        refresh_token: data.refresh_token,
        expires_in: data.expires_in,
      });
    } catch (err) {
      console.error('Token refresh error:', err);
      return res.status(502).json({ error: 'Upstream error' });
    }
  });

  // ── GET /health ─────────────────────────────────────────────────────────────
  app.get('/health', (_req, res) => res.json({ status: 'ok' }));

  return app;
}
