/**
 * WatchBuddy — Trakt Token Proxy
 *
 * Purpose:
 *   Exchange a Trakt device auth_code for access/refresh tokens without
 *   exposing the client_secret in the Android APK.
 *
 * Endpoints:
 *   POST /trakt/token         — exchange device auth_code for tokens
 *   POST /trakt/token/refresh — refresh an expired access token
 *   GET  /health              — liveness check
 *
 * Nothing is stored server-side. The proxy is purely a pass-through
 * that injects the server-side client_secret.
 */

import 'dotenv/config';
import express from 'express';
import fetch from 'node-fetch';
import rateLimit from 'express-rate-limit';
import cors from 'cors';

const app = express();
app.use(express.json());

// CORS — restrict to configured origins or disable entirely
const allowedOrigins = process.env.ALLOWED_ORIGINS?.split(',').map(o => o.trim()).filter(Boolean);
app.use(cors(allowedOrigins?.length ? { origin: allowedOrigins } : { origin: false }));

// Rate limiting — Trakt allows 1000 calls/5min per app
const limiter = rateLimit({
  windowMs: 60 * 1000,  // 1 minute
  max: 60,              // 60 requests per minute per IP
  message: { error: 'Too many requests, please try again later.' }
});
app.use('/trakt', limiter);

const TRAKT_API = 'https://api.trakt.tv';
const FETCH_TIMEOUT_MS = 15_000;
const { TRAKT_CLIENT_ID, TRAKT_CLIENT_SECRET, PORT = 3000 } = process.env;

if (!TRAKT_CLIENT_ID || !TRAKT_CLIENT_SECRET) {
  console.error('ERROR: TRAKT_CLIENT_ID and TRAKT_CLIENT_SECRET must be set in .env');
  process.exit(1);
}

/** Input validation: alphanumeric string with limited length. */
function isValidToken(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 256 && /^[a-zA-Z0-9_\-]+$/.test(value);
}

/** Fetch with timeout via AbortController. */
async function fetchWithTimeout(url, options) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timeout);
  }
}

// ── POST /trakt/token ─────────────────────────────────────────────────────────
// Body: { "code": "<device_code>" }
// Calls Trakt /oauth/device/token with server-side secret injected
app.post('/trakt/token', async (req, res) => {
  const { code } = req.body;
  if (!code) return res.status(400).json({ error: 'Missing code' });
  if (!isValidToken(code)) return res.status(400).json({ error: 'Invalid code format' });

  try {
    const traktRes = await fetchWithTimeout(`${TRAKT_API}/oauth/device/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        code,
        client_id: TRAKT_CLIENT_ID,
        client_secret: TRAKT_CLIENT_SECRET   // injected here — never sent to APK
      })
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
      scope: data.scope
    });
  } catch (err) {
    if (err.name === 'AbortError') {
      console.error('Token exchange timed out');
      return res.status(504).json({ error: 'Upstream timeout' });
    }
    console.error('Token exchange error:', err);
    return res.status(502).json({ error: 'Upstream error' });
  }
});

// ── POST /trakt/token/refresh ─────────────────────────────────────────────────
// Body: { "refresh_token": "<token>" }
app.post('/trakt/token/refresh', async (req, res) => {
  const { refresh_token } = req.body;
  if (!refresh_token) return res.status(400).json({ error: 'Missing refresh_token' });
  if (!isValidToken(refresh_token)) return res.status(400).json({ error: 'Invalid refresh_token format' });

  try {
    const traktRes = await fetchWithTimeout(`${TRAKT_API}/oauth/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        refresh_token,
        client_id: TRAKT_CLIENT_ID,
        client_secret: TRAKT_CLIENT_SECRET,
        grant_type: 'refresh_token'
      })
    });

    const data = await traktRes.json();
    if (!traktRes.ok) return res.status(traktRes.status).json(data);

    return res.json({
      access_token: data.access_token,
      refresh_token: data.refresh_token,
      expires_in: data.expires_in
    });
  } catch (err) {
    if (err.name === 'AbortError') {
      console.error('Token refresh timed out');
      return res.status(504).json({ error: 'Upstream timeout' });
    }
    console.error('Token refresh error:', err);
    return res.status(502).json({ error: 'Upstream error' });
  }
});

// ── GET /health ───────────────────────────────────────────────────────────────
app.get('/health', (_req, res) => res.json({ status: 'ok' }));

app.listen(PORT, () => {
  console.log(`WatchBuddy token proxy running on port ${PORT}`);
});
