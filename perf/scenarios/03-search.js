// Phase 3 (PBL6-76): keyword search + autocomplete against NFR-PERF-02.
// Needs ai-search-service behind the gateway and Elasticsearch indexed with the seed catalog.
//
//   k6 run [-e PROFILE=load] perf/scenarios/03-search.js
import { get } from '../lib/api.js';
import { SEARCH_P95_MS, SUGGEST_P95_MS, SUMMARY_TREND_STATS, scenarioFor, thresholdsFor } from '../lib/config.js';
import { searchSession } from '../flows/search.js';

export const options = {
  scenarios: { searchers: scenarioFor('searcher') },
  thresholds: thresholdsFor({
    'GET /api/ai/search': SEARCH_P95_MS,
    'GET /api/ai/search/suggestions': SUGGEST_P95_MS,
  }),
  summaryTrendStats: SUMMARY_TREND_STATS,
};

export function setup() {
  const { response } = get('/api/ai/search/health', { endpoint: 'setup:search-health' });
  if (response.status !== 200) {
    throw new Error(`ai-search-service is not reachable through the gateway (HTTP ${response.status}); see PBL6-76`);
  }
  const { data } = get('/api/ai/search?keyword=a&size=1', { endpoint: 'setup:search-probe', expect: [200, 400] });
  if (data && data.totalElements === 0) console.warn('search index looks empty: results will not be representative');
}

export function searcher() {
  searchSession();
}
