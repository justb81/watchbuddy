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

  it('response conforms to the v8 catalog schema', async () => {
    const app = buildApp();
    const res = await request(app).get('/provider-catalog');
    expect(res.status).toBe(200);

    const body = res.body;
    expect(typeof body.version).toBe('number');
    expect(body.version).toBe(8);
    expect(typeof body.lastUpdated).toBe('string');
    expect(Array.isArray(body.providers)).toBe(true);
    expect(body.providers.length).toBeGreaterThan(0);

    for (const provider of body.providers) {
      expect(Array.isArray(provider.tmdbProviderIds)).toBe(true);
      expect(provider.tmdbProviderIds.length).toBeGreaterThan(0);
      expect(provider).not.toHaveProperty('tmdbProviderId');
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

  it('contains known providers by TMDB ID', async () => {
    const app = buildApp();
    const res = await request(app).get('/provider-catalog');
    expect(res.status).toBe(200);

    const allIds = res.body.providers.flatMap((p) => p.tmdbProviderIds);
    expect(allIds).toContain(8); // Netflix
    expect(allIds).toContain(119); // Prime Video
    expect(allIds).toContain(9); // Amazon Video (merged into Prime Video entry)
    expect(allIds).toContain(337); // Disney+
    expect(allIds).toContain(2184); // Joyn
    expect(allIds).toContain(192); // YouTube
  });

  it('has exactly one entry covering both Amazon TMDB IDs 9 and 119', async () => {
    const app = buildApp();
    const res = await request(app).get('/provider-catalog');
    expect(res.status).toBe(200);

    const amazonEntries = res.body.providers.filter(
      (p) => p.tmdbProviderIds.includes(9) || p.tmdbProviderIds.includes(119)
    );
    expect(amazonEntries).toHaveLength(1);
    expect(amazonEntries[0].tmdbProviderIds).toContain(9);
    expect(amazonEntries[0].tmdbProviderIds).toContain(119);
    expect(amazonEntries[0].justWatchTechnicalNames).toContain('amazonprime');
    expect(amazonEntries[0].justWatchTechnicalNames).toContain('amazonprimevideowithads');
  });

  it('Joyn entry uses the correct Android TV package and not the old Seven.TV package', async () => {
    const app = buildApp();
    const res = await request(app).get('/provider-catalog');
    expect(res.status).toBe(200);

    const joyn = res.body.providers.find((p) => p.tmdbProviderIds.includes(2184));
    expect(joyn).toBeDefined();
    expect(joyn.androidPackages.tv).toContain('de.prosiebensat1.joyn.tv');
    expect(joyn.androidPackages.tv).not.toContain('de.prosiebensat1digital.seventv');
    expect(joyn.justWatchTechnicalNames).toContain('joynde');
  });

  it('contains JustWatch technical names for known providers', async () => {
    const app = buildApp();
    const res = await request(app).get('/provider-catalog');
    expect(res.status).toBe(200);

    const netflix = res.body.providers.find((p) => p.tmdbProviderIds.includes(8));
    expect(netflix).toBeDefined();
    expect(netflix.justWatchTechnicalNames).toContain('netflix');
  });

  it('is not rate-limited by the standard limiter for typical usage', async () => {
    const app = buildApp();
    for (let i = 0; i < 5; i++) {
      const res = await request(app).get('/provider-catalog');
      expect(res.status).toBe(200);
    }
  });
});
