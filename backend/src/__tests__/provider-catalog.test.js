import { describe, it, expect } from 'vitest';
import request from 'supertest';
import { createApp } from '../app.js';

/** Build a minimal app instance — credentials are required by createApp. */
function buildApp() {
  return createApp({
    clientId: 'test-client-id',
    clientSecret: 'test-client-secret',
    healthCacheTtlMs: 0,
  });
}

describe('GET /provider-catalog', () => {
  it('returns 200 with JSON content-type', async () => {
    const app = buildApp();
    const res = await request(app).get('/provider-catalog');
    expect(res.status).toBe(200);
    expect(res.headers['content-type']).toMatch(/application\/json/);
  });

  it('response conforms to the catalog schema', async () => {
    const app = buildApp();
    const res = await request(app).get('/provider-catalog');
    expect(res.status).toBe(200);

    const body = res.body;
    expect(typeof body.version).toBe('number');
    expect(typeof body.lastUpdated).toBe('string');
    expect(Array.isArray(body.providers)).toBe(true);
    expect(body.providers.length).toBeGreaterThan(0);

    for (const provider of body.providers) {
      expect(typeof provider.tmdbProviderId).toBe('number');
      expect(typeof provider.name).toBe('string');
      expect(Array.isArray(provider.regions)).toBe(true);
      expect(typeof provider.androidPackages).toBe('object');
      expect(Array.isArray(provider.androidPackages.tv)).toBe(true);
      expect(Array.isArray(provider.androidPackages.phone)).toBe(true);
      expect(Array.isArray(provider.justWatchTechnicalNames)).toBe(true);
    }
  });

  it('includes a Cache-Control header', async () => {
    const app = buildApp();
    const res = await request(app).get('/provider-catalog');
    expect(res.status).toBe(200);
    expect(res.headers['cache-control']).toBeDefined();
  });

  it('includes an ETag header', async () => {
    const app = buildApp();
    const res = await request(app).get('/provider-catalog');
    expect(res.status).toBe(200);
    expect(res.headers['etag']).toBeDefined();
    expect(res.headers['etag']).toMatch(/^"[a-f0-9]+"$/);
  });

  it('returns 304 when If-None-Match matches the ETag', async () => {
    const app = buildApp();
    const first = await request(app).get('/provider-catalog');
    expect(first.status).toBe(200);
    const etag = first.headers['etag'];

    const second = await request(app).get('/provider-catalog').set('If-None-Match', etag);
    expect(second.status).toBe(304);
  });

  it('returns 200 when If-None-Match does not match', async () => {
    const app = buildApp();
    const res = await request(app).get('/provider-catalog').set('If-None-Match', '"stale-etag"');
    expect(res.status).toBe(200);
  });

  it('contains known providers from the existing hardcoded catalog', async () => {
    const app = buildApp();
    const res = await request(app).get('/provider-catalog');
    expect(res.status).toBe(200);

    const ids = res.body.providers.map((p) => p.tmdbProviderId);
    expect(ids).toContain(8); // Netflix
    expect(ids).toContain(119); // Prime Video
    expect(ids).toContain(337); // Disney+
    expect(ids).toContain(2184); // Joyn
    expect(ids).toContain(192); // YouTube
  });

  it('contains JustWatch technical names for known providers', async () => {
    const app = buildApp();
    const res = await request(app).get('/provider-catalog');
    expect(res.status).toBe(200);

    const netflix = res.body.providers.find((p) => p.tmdbProviderId === 8);
    expect(netflix).toBeDefined();
    expect(netflix.justWatchTechnicalNames).toContain('netflix');

    const joyn = res.body.providers.find((p) => p.tmdbProviderId === 2184);
    expect(joyn).toBeDefined();
    expect(joyn.justWatchTechnicalNames).toContain('joynde');
  });

  it('is not rate-limited by the standard limiter for typical usage', async () => {
    const app = buildApp();
    for (let i = 0; i < 5; i++) {
      const res = await request(app).get('/provider-catalog');
      expect(res.status).toBe(200);
    }
  });
});
