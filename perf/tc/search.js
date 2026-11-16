// PERF-SRCH-01/02 (NFR-PERF-02): keyword search p95 <= 1 s, autocomplete p95 <= 300 ms.
//   SRCH-01 baseline 50 VUs x 5 min | SRCH-02 load 200 VUs x 10 min
// Results must come from the search service itself: a fallback answer means the
// requirement was not met (search_fallback_used must stay 0).
import { SUMMARY_TREND_STATS, buildThresholds, scenarioFor } from '../lib/config.js';
import { SEARCH } from '../lib/endpoints.js';
import { MANIFEST_INFO } from '../lib/manifest.js';
import { requireFixture, requireServices } from '../lib/probe.js';
import { searchJourney } from '../flows/search.js';
import { writeSummary } from '../lib/summary.js';

export const options = {
  scenarios: { search: scenarioFor('search', 200) },
  thresholds: buildThresholds({
    endpoints: SEARCH,
    custom: [
      ['search_served', 'rate>=0.99', 'acceptance'],
      ['search_fallback_used', 'rate==0', 'acceptance'],
    ],
  }),
  summaryTrendStats: SUMMARY_TREND_STATS,
};

export function setup() {
  requireFixture(MANIFEST_INFO.keywords.length > 0, 'search keywords from the catalog');
  requireServices(['search']);
}

export function search() {
  searchJourney();
}

export function handleSummary(data) {
  return writeSummary(data);
}
