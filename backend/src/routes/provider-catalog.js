/**
 * GET /provider-catalog
 *
 * Serves the versioned provider-catalog JSON from backend/src/data/provider-catalog.json.
 * Supports ETag / If-None-Match for efficient client polling (24 h refresh interval).
 *
 * No authentication required — this is public, read-only metadata.
 */

import { createHash } from 'crypto';
import { readFileSync } from 'fs';
import { Router } from 'express';

const catalogPath = new URL('../data/provider-catalog.json', import.meta.url);
const catalogJson = readFileSync(catalogPath, 'utf-8');
const catalogEtag = `"${createHash('sha256').update(catalogJson).digest('hex').slice(0, 16)}"`;

export function createProviderCatalogRouter() {
  const router = Router();

  router.get('/provider-catalog', (_req, res) => {
    const ifNoneMatch = _req.headers['if-none-match'];
    if (ifNoneMatch === catalogEtag) {
      return res.status(304).end();
    }
    res.setHeader('ETag', catalogEtag);
    res.setHeader('Cache-Control', 'public, max-age=86400');
    res.setHeader('Content-Type', 'application/json');
    return res.status(200).send(catalogJson);
  });

  return router;
}
