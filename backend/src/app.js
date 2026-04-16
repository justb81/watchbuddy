/**
 * WatchBuddy — Trakt Token Proxy (Express app factory)
 *
 * Separated from index.js so the app can be imported in tests without
 * starting the HTTP server or requiring environment variables.
 */

import express from 'express';
import rateLimit from 'express-rate-limit';

/**
 * Creates and configures the Express application.
 *
 * @param {object} config
 * @param {string} config.clientId       - Trakt client ID
 * @param {string} config.clientSecret   - Trakt client secret
 * @param {string} [config.traktApi]     - Trakt API base URL (default: https://api.trakt.tv)
 * @param {Function} [config.fetchFn]    - fetch implementation (default: global fetch)
 * @returns {import('express').Express}
 */
export function createApp({ clientId, clientSecret, traktApi = 'https://api.trakt.tv', fetchFn = fetch }) {
  const app = express();
  // Trust the first proxy hop so express-rate-limit reads the real client IP
  // from X-Forwarded-For instead of treating all requests as one IP.
  app.set('trust proxy', 1);
  app.use(express.json());

  // Rate limiting — Trakt allows 1000 calls/5 min per app
  const limiter = rateLimit({
    windowMs: 60 * 1000,
    max: 60,
    message: { error: 'Too many requests, please try again later.' },
  });
  app.use('/trakt', limiter);

  // ── POST /trakt/token ────────────────────────────────────────────────────────
  // Body: { "code": "<device_code>" }
  // Calls Trakt /oauth/device/token with server-side secret injected
  app.post('/trakt/token', async (req, res) => {
    const { code } = req.body;
    if (!code) return res.status(400).json({ error: 'Missing code' });

    const url = `${traktApi}/oauth/device/token`;
    try {
      const traktRes = await fetchFn(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code, client_id: clientId, client_secret: clientSecret }),
      });

      let rawBody;
      let data;
      try {
        rawBody = await traktRes.text();
        data = JSON.parse(rawBody);
      } catch (_parseErr) {
        const bodySnippet = rawBody ? rawBody.slice(0, 200) : '(no body)';
        console.error(
          `Token exchange: Trakt returned non-JSON response (HTTP ${traktRes.status}): ${bodySnippet}`,
        );
        return res.status(502).json({
          error: `Upstream returned non-JSON response (HTTP ${traktRes.status})`,
        });
      }

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
      console.error('Token exchange error:', err.message);
      return res.status(502).json({ error: 'Upstream error' });
    }
  });

  // ── POST /trakt/token/refresh ────────────────────────────────────────────────
  // Body: { "refresh_token": "<token>" }
  app.post('/trakt/token/refresh', async (req, res) => {
    const { refresh_token } = req.body;
    if (!refresh_token) return res.status(400).json({ error: 'Missing refresh_token' });

    const url = `${traktApi}/oauth/token`;
    try {
      const traktRes = await fetchFn(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          refresh_token,
          client_id: clientId,
          client_secret: clientSecret,
          grant_type: 'refresh_token',
        }),
      });

      let rawBody;
      let data;
      try {
        rawBody = await traktRes.text();
        data = JSON.parse(rawBody);
      } catch (_parseErr) {
        const bodySnippet = rawBody ? rawBody.slice(0, 200) : '(no body)';
        console.error(
          `Token refresh: Trakt returned non-JSON response (HTTP ${traktRes.status}): ${bodySnippet}`,
        );
        return res.status(502).json({
          error: `Upstream returned non-JSON response (HTTP ${traktRes.status})`,
        });
      }

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
      });
    } catch (err) {
      console.error('Token refresh error:', err.message);
      return res.status(502).json({ error: 'Upstream error' });
    }
  });

  // ── GET /health ──────────────────────────────────────────────────────────────
  app.get('/health', (_req, res) => res.json({ status: 'ok' }));

  return app;
}
