// Search journey (UC-03, FR-SRCH-01/02/03, NFR-PERF-02): type a keyword (one
// autocomplete request per keystroke from 2 characters), run the search, sometimes
// add a price/category filter or open page 2.
//
// UC-03 exception flow: when the search service fails, the client falls back to
// the catalog's basic name search. The fallback is recorded separately so a run
// can tell "served by AI search" from "served by the fallback".
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { get, pickOne, query, tagPhase, think } from '../lib/api.js';
import { MANIFEST_INFO } from '../lib/manifest.js';

export const searchServed = new Rate('search_served'); // a result list reached the user (AI or fallback)
export const searchFallback = new Rate('search_fallback_used'); // share answered by the fallback

// Accented, unaccented, slightly misspelled and synonym forms (FR-SRCH-01/02).
const VARIANTS = [
  (k) => k,
  (k) => k.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'D'),
  (k) => (k.length > 4 ? k.slice(0, -2) + k.slice(-1) + k.slice(-2, -1) : k), // swapped letters
];
const SYNONYMS = ['áo phông', 'ao phong', 'giày thể thao', 'điện thoại', 'nồi cơm điện'];

export function keyword() {
  if (Math.random() < 0.1) return pickOne(SYNONYMS);
  const base = pickOne(MANIFEST_INFO.keywords.length ? MANIFEST_INFO.keywords : ['áo thun']);
  return pickOne(VARIANTS)(base);
}

const AI_DOWN = [200, 404, 429, 500, 502, 503, 504]; // statuses a client survives when the AI service fails
export const suggestFailed = new Rate('search_suggest_failed');

// tolerate: the AI service may be stopped (LOAD-07); a failed suggestion is shown as
// "no suggestions", recorded in search_suggest_failed, not as an HTTP failure.
export function typeAhead(term, { tolerate = false } = {}) {
  for (let length = 2; length <= term.length; length += 1 + Math.floor(Math.random() * 2)) {
    const { response, data } = get(`/api/ai/search/suggestions${query({ keyword: term.slice(0, length), limit: 8 })}`, {
      endpoint: 'GET /api/ai/search/suggestions',
      expect: tolerate ? AI_DOWN : [200],
    });
    suggestFailed.add(response.status !== 200);
    tagPhase();
    if (response.status === 200) check(data, { 'suggestions: a list': (d) => Array.isArray(d) || (d && Array.isArray(d.suggestions)) });
    sleep(0.15 + Math.random() * 0.25); // keystroke gap
  }
}

// UC-03 basic name search at the catalog (used when the search service fails).
function fallback(term) {
  const fb = get(`/api/products${query({ q: term, page: 0, size: 20 })}`, { endpoint: 'GET /api/products?q (fallback)' });
  searchServed.add(!!fb.data);
  searchFallback.add(true);
  return fb.data;
}

export function search(term, extra = {}, { aiAvailable = true } = {}) {
  if (!aiAvailable) return fallback(term);
  const ai = get(`/api/ai/search${query({ keyword: term, page: 0, size: 20, ...extra })}`, {
    endpoint: 'GET /api/ai/search',
    expect: AI_DOWN,
  });
  if (ai.response.status === 200 && ai.data) {
    searchServed.add(true);
    searchFallback.add(false);
    tagPhase();
    check(ai.data, { 'search: result list': (d) => Array.isArray(d.content || d.items || d.results) });
    return ai.data;
  }
  return fallback(term); // UC-03: search service error -> basic name search
}

// aiAvailable=false: the search service is known to be down (not deployed), so the
// client skips autocomplete and uses the basic search directly (UC-03).
export function searchJourney({ aiAvailable = true, tolerate = false } = {}) {
  const term = keyword();
  if (aiAvailable) typeAhead(term, { tolerate });
  const first = search(term, {}, { aiAvailable });
  think(1, 3);
  if (Math.random() < 0.3) {
    const c = pickOne(MANIFEST_INFO.categories);
    search(term, { categoryId: c.id, sort: 'PRICE_ASC' }, { aiAvailable });
    think(1, 3);
  } else if (aiAvailable && first && Math.random() < 0.25) {
    get(`/api/ai/search${query({ keyword: term, page: 1, size: 20 })}`, { endpoint: 'GET /api/ai/search', expect: AI_DOWN });
    think(1, 3);
  }
}
