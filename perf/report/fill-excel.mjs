#!/usr/bin/env node
// Writes the results of one round into the "Performance Test" sheet of the test
// report: Round N status (Passed / Failed / Pending), Test date, Tester. The sheet's
// own formulas (rows 6-8, Test Statistics) recalculate when the file is opened.
// It also writes perf/results/R<n>/report.md + report.csv with the actual values
// and evidence paths, since the sheet has no Actual / Evidence / Defect column.
//
//   node perf/report/fill-excel.mjs --round=1
//   node perf/report/fill-excel.mjs --round=1 --in=docs/VMarket_Test_Report.xlsx --out=perf/results/VMarket_Test_Report.R1.xlsx
//   node perf/report/fill-excel.mjs --round=1 --in-place      (overwrites --in)
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { columnValues, entryText, readZip, setCell, setEntryText, sharedStrings, sheetPath, writeZip } from './xlsx.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '../..');
const args = process.argv.slice(2);
const option = (name, fallback) => {
  const hit = args.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : fallback;
};

const SHEET = 'Performance Test';
const COLUMNS = { 1: ['F', 'G', 'H'], 2: ['I', 'J', 'K'], 3: ['L', 'M', 'N'] }; // status, date, tester

const round = option('round');
if (!COLUMNS[round]) {
  console.error('--round=1|2|3 is required');
  process.exit(1);
}
const input = resolve(repoRoot, option('in', 'docs/VMarket_Test_Report.xlsx'));
const output = args.includes('--in-place') ? input : resolve(repoRoot, option('out', `perf/results/VMarket_Test_Report.R${round}.xlsx`));
const resultsDir = resolve(repoRoot, 'perf/results', `R${round}`);

const runs = existsSync(resultsDir)
  ? readdirSync(resultsDir)
      .filter((d) => existsSync(resolve(resultsDir, d, 'run.json')))
      .map((d) => ({
        run: JSON.parse(readFileSync(resolve(resultsDir, d, 'run.json'), 'utf8')),
        summary: existsSync(resolve(resultsDir, d, 'summary.json')) ? JSON.parse(readFileSync(resolve(resultsDir, d, 'summary.json'), 'utf8')) : null,
      }))
  : [];
if (!runs.length) {
  console.error(`No results in ${resultsDir}. Run: node perf/run.mjs --round=${round} ...`);
  process.exit(1);
}

// "2026-11-08T22:15:00.000+07:00" -> "08/11/2026" (Asia/Bangkok, as the sheet asks)
const dateOf = (iso) => `${iso.slice(8, 10)}/${iso.slice(5, 7)}/${iso.slice(0, 4)}`;

// ---------- Excel ----------
const entries = readZip(readFileSync(input));
const path = sheetPath(entries, SHEET);
const sheet = entries.find((e) => e.name === path);
let xml = entryText(sheet);
const ids = columnValues(xml, 'A', sharedStrings(entries));
const rowOf = Object.fromEntries(Object.entries(ids).filter(([, v]) => /^PERF-/.test(v || '')).map(([row, v]) => [v, row]));

const [statusCol, dateCol, testerCol] = COLUMNS[round];
for (const { run } of runs) {
  const row = rowOf[run.tc];
  if (!row) {
    console.warn(`! ${run.tc} has no row in "${SHEET}", skipped`);
    continue;
  }
  xml = setCell(xml, `${statusCol}${row}`, run.verdict);
  xml = setCell(xml, `${dateCol}${row}`, dateOf(run.startedAt), `${testerCol}${row}`);
  xml = setCell(xml, `${testerCol}${row}`, run.tester);
  console.log(`  ${run.tc.padEnd(16)} row ${row}: ${run.verdict}${run.reason ? ` (${run.reason})` : ''}`);
}
setEntryText(sheet, xml);

// Recalculate the COUNTIFS/CHOOSE formulas when the workbook is opened.
const workbook = entries.find((e) => e.name === 'xl/workbook.xml');
let wb = entryText(workbook);
wb = /<calcPr[^>]*\/>/.test(wb)
  ? wb.replace(/<calcPr([^>]*?)\s*\/>/, (m, attrs) => `<calcPr${attrs.replace(/\sfullCalcOnLoad="[^"]*"/, '')} fullCalcOnLoad="1"/>`)
  : wb.replace('</workbook>', '<calcPr fullCalcOnLoad="1"/></workbook>');
setEntryText(workbook, wb);
writeFileSync(output, writeZip(entries));
console.log(`\nExcel: ${output}`);

// ---------- report.md / report.csv (Actual / Evidence) ----------
const n = (v, d = 0) => (v === undefined || v === null ? '-' : Number(v).toFixed(d));
const md = [`# Performance test - Round ${round}`, '', '| TC | Result | Reason | Endpoint (phase) | Samples | p95 ms | p99 ms | Failure | Checks | VUs max | Evidence |', '|---|---|---|---|---|---|---|---|---|---|---|'];
const csv = ['tc,result,reason,started_at,endpoint,phase,samples,p50_ms,p95_ms,p99_ms,max_ms,failure_rate,check_rate,vus_max,rps,evidence'];
for (const { run, summary } of runs.sort((a, b) => a.run.tc.localeCompare(b.run.tc))) {
  const accept = summary ? summary.acceptPhases : [];
  const eps = summary ? Object.values(summary.endpoints).filter((e) => accept.includes(e.phase)) : [];
  const evidence = `perf/results/R${round}/${run.tc}/`;
  if (!eps.length) eps.push({ endpoint: '-', phase: '-' });
  for (const e of eps) {
    md.push(`| ${run.tc} | ${run.verdict} | ${run.reason || ''} | ${e.endpoint} (${e.phase}) | ${n(e.count)} | ${n(e.p95, 1)} | ${n(e.p99, 1)} | ${summary ? n(summary.failureRate * 100, 2) + '%' : '-'} | ${summary ? n(summary.checkRate * 100, 2) + '%' : '-'} | ${summary ? n(summary.vusMax) : '-'} | ${evidence} |`);
    csv.push([run.tc, run.verdict, `"${(run.reason || '').replace(/"/g, "'")}"`, run.startedAt, `"${e.endpoint}"`, e.phase, n(e.count), n(e.p50, 1), n(e.p95, 1), n(e.p99, 1), n(e.max, 1), summary ? n(summary.failureRate, 4) : '', summary ? n(summary.checkRate, 4) : '', summary ? n(summary.vusMax) : '', summary ? n(summary.rps, 2) : '', evidence].join(','));
  }
  if (summary && summary.custom) {
    // Business metrics (invariants, LCP, shipper latency, index sync, fallback share...)
    const shown = Object.entries(summary.custom)
      .filter(([, v]) => v && (v.count !== undefined || v.rate !== undefined || v['p(95)'] !== undefined))
      .map(([k, v]) => `${k}: ${v['p(95)'] !== undefined ? `p95 ${n(v['p(95)'], 0)}` : v.rate !== undefined && v.count === undefined ? `${n(v.rate * 100, 2)}%` : `count ${n(v.count)}`}`);
    if (shown.length) md.push(`| | | ${shown.join('; ')} | | | | | | | | |`);
  }
  if (summary && summary.breakingPoint) {
    const bp = summary.breakingPoint;
    // Summaries written before steps carried `reached` have no completeness flag:
    // derive it from the per-step request count so an aborted run is never shown as
    // "every step good".
    const reached = (bp.steps || []).filter((s) => s.reached !== undefined ? s.reached : (s.requests || 0) > 0);
    const known = (bp.steps || []).some((s) => s.reached !== undefined || s.requests !== undefined);
    const text = bp.complete === false
      ? `stress incomplete: run stopped at the ${bp.reachedVus} VU step; last good step ${bp.lastGoodVus} VUs, no failing step reached`
      : !known
        ? `stress: per-step coverage not recorded (summary predates the fix); breaking point not established`
        : `stress: last good step ${bp.lastGoodVus} VUs, first failing step ${bp.firstBadVus || 'none'} (${reached.length}/${bp.steps.length} steps run)`;
    md.push(`| | | ${text} | | | | | | | | |`);
  }
}
md.push('', 'Server-side evidence (CPU/RAM/GC, DB pool, Redis) comes from Grafana for the same time range; attach screenshots before marking a soak/spike as final.');
writeFileSync(resolve(resultsDir, 'report.md'), md.join('\n') + '\n');
writeFileSync(resolve(resultsDir, 'report.csv'), '﻿' + csv.join('\n') + '\n');
console.log(`Report: ${resolve(resultsDir, 'report.md')} (+ report.csv)`);
