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
 *   GET  /health              — unauthenticated liveness check ({ status: 'ok'|'unhealthy' })
 *   GET  /health/detailed     — authenticated verbose health check (requires X-Health-Token)
 *
 * Nothing is stored server-side. The proxy is purely a pass-through
 * that injects the server-side client_secret.
 */

import { readFileSync } from 'fs';
import { createApp } from './app.js';

const { version } = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf-8'));

const {
  TRAKT_CLIENT_ID,
  TRAKT_CLIENT_SECRET,
  PORT = 3000,
  DEBUG_MODE,
  FETCH_TIMEOUT_MS,
  HEALTH_TOKEN,
} = process.env;

if (!TRAKT_CLIENT_ID || !TRAKT_CLIENT_SECRET) {
  console.error(
    'ERROR: TRAKT_CLIENT_ID and TRAKT_CLIENT_SECRET must be set as environment variables'
  );
  process.exit(1);
}

const debug = DEBUG_MODE === 'true';

let fetchTimeoutMs;
if (FETCH_TIMEOUT_MS !== undefined && FETCH_TIMEOUT_MS !== '') {
  const parsed = parseInt(FETCH_TIMEOUT_MS, 10);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    console.warn(
      `FETCH_TIMEOUT_MS="${FETCH_TIMEOUT_MS}" is not a valid positive integer — using default`
    );
  } else {
    fetchTimeoutMs = parsed;
  }
}

if (!HEALTH_TOKEN) {
  console.warn(
    'HEALTH_TOKEN is not set — GET /health/detailed is disabled. Set HEALTH_TOKEN to enable the authenticated verbose health endpoint.'
  );
}

const app = createApp({
  clientId: TRAKT_CLIENT_ID,
  clientSecret: TRAKT_CLIENT_SECRET,
  version,
  debug,
  fetchTimeoutMs,
  healthToken: HEALTH_TOKEN,
});

const server = app.listen(PORT, () => {
  console.log(`WatchBuddy token proxy running on port ${PORT}`);
  if (debug) {
    console.log('Debug mode enabled — request logging is active');
  }
  // Verify Trakt credentials in the background — health endpoint
  // will report "starting" until this completes.
  app.verifyCredentials();
});

const shutdown = () => {
  app.clearRetryTimer();
  server.close(() => process.exit(0));
  // Force exit if the server hasn't closed within 10 s (e.g. keep-alive connections).
  setTimeout(() => process.exit(1), 10_000).unref();
};
['SIGTERM', 'SIGINT'].forEach((s) => process.on(s, shutdown));
