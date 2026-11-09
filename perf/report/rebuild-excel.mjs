#!/usr/bin/env node
// Rewrites the performance part of the test report from perf/report/test-cases.mjs:
//   - "Performance Test": header block, the 3 function groups and their test cases,
//     the round formulas (rows 4, 6-8), conditional formats and the status drop-down
//   - "Test Cases": the 3 performance rows of the function table, the PERF fixtures
//     and the performance workload rules (section 10.1)
//   - "Test Statistics" / "Cover": the total number of test cases
// Styles are reused from the existing sheet; every other sheet stays untouched.
// The original file is backed up next to it first.
//
//   node perf/report/rebuild-excel.mjs [--in=docs/VMarket_Test_Report.xlsx] [--out=<path>]
import { copyFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { entryText, readZip, setCell, setEntryText, sheetPath, writeZip } from './xlsx.mjs';
import { FUNCTIONS, OWNER, REVIEWER, excelRow } from './test-cases.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '../..');
const args = process.argv.slice(2);
const option = (name, fallback) => {
  const hit = args.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : fallback;
};
const input = resolve(repoRoot, option('in', 'docs/VMarket_Test_Report.xlsx'));
const output = resolve(repoRoot, option('out', option('in', 'docs/VMarket_Test_Report.xlsx')));
if (output === input) {
  const backup = input.replace(/\.xlsx$/, '.before-perf-srs.xlsx');
  if (!existsSync(backup)) copyFileSync(input, backup);
  console.log(`backup: ${backup}`);
}

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const str = (ref, s, text) => `<c r="${ref}" s="${s}" t="inlineStr"><is><t xml:space="preserve">${esc(text)}</t></is></c>`;
const empty = (ref, s) => `<c r="${ref}" s="${s}"/>`;
const formula = (ref, s, f, v) => `<c r="${ref}" s="${s}"><f>${esc(f)}</f><v>${v}</v></c>`;
const COLS = 'ABCDEFGHIJKLMNO'.split('');

function setFormula(xml, ref, f, v) {
  const re = new RegExp(`<c r="${ref}"([^>]*?)(?:/>|>[\\s\\S]*?</c>)`);
  const m = xml.match(re);
  if (!m) throw new Error(`cell ${ref} not found`);
  const s = (m[1].match(/\ss="(\d+)"/) || [])[1];
  return xml.replace(re, () => formula(ref, s, f, v)); // function: "$F$12" must not be read as $1
}

const entries = readZip(readFileSync(input));
const part = (sheet) => entries.find((e) => e.name === sheetPath(entries, sheet));

// ---------------------------------------------------------------- Performance Test
{
  const sheet = part('Performance Test');
  let xml = entryText(sheet);
  const total = FUNCTIONS.reduce((n, f) => n + f.cases.length, 0);

  // Layout: row 11 first function header, its cases, next header, ...
  const ranges = [];
  let r = 11;
  const rows = [];
  for (const fn of FUNCTIONS) {
    const header = r++;
    const first = r;
    rows.push(
      `<row r="${header}" spans="1:15" ht="28.15" customHeight="1">${COLS.map((c) =>
        c === 'A' ? str(`A${header}`, 39, `${fn.title} — ${fn.cases.length} TC`) : c === 'F' ? str(`F${header}`, 39, `${fn.trace} | Phụ trách ${OWNER}, reviewer ${REVIEWER}`) : empty(`${c}${header}`, 39),
      ).join('')}</row>`,
    );
    for (const c of fn.cases) {
      const x = excelRow(fn, c);
      const n = r++;
      rows.push(
        `<row r="${n}" spans="1:15" ht="215" customHeight="1">` +
          str(`A${n}`, 10, x.id) + str(`B${n}`, 9, x.description) + str(`C${n}`, 9, x.procedure) + str(`D${n}`, 9, x.expected) + str(`E${n}`, 9, x.preconditions) +
          str(`F${n}`, 11, 'Pending') + empty(`G${n}`, 12) + str(`H${n}`, 13, OWNER) +
          str(`I${n}`, 11, 'Pending') + empty(`J${n}`, 12) + str(`K${n}`, 13, OWNER) +
          str(`L${n}`, 11, 'Pending') + empty(`M${n}`, 12) + str(`N${n}`, 13, OWNER) +
          str(`O${n}`, 9, x.note) +
          '</row>',
      );
    }
    ranges.push({ header, first, last: r - 1 });
  }
  const last = r - 1;

  // Replace every row from 11 on.
  const start = xml.indexOf('<row r="11"');
  const end = xml.indexOf('</sheetData>');
  xml = xml.slice(0, start) + rows.join('') + xml.slice(end);
  xml = xml.replace(/<dimension ref="[^"]+"\/>/, `<dimension ref="A1:O${last}"/>`);

  // Header block texts and formulas.
  xml = setCell(xml, 'B3', 'SRS v1.1 mục 6.1: NFR-PERF-01, NFR-PERF-02, NFR-PERF-03, NFR-PERF-04, NFR-PERF-05 | liên quan: FR-SRCH-04, FR-ORD-01, FR-PROD-02, UC-03/06, NFR-REL-02, NFR-USA-03');
  xml = setCell(xml, 'F3', `ENV-P riêng theo SRS 5.2 (≥ 4 vCPU, 8 GB RAM); k6 + Prometheus/Grafana trên máy riêng; 850 buyer, 50 shipper, ≥ 1000 sản phẩm ACTIVE. Tách tải capacity khỏi kiểm thử chức năng.`);
  xml = setCell(xml, 'F4', 'Lịch: D1 fixture + smoke; D2 READ, SRCH; D3 LOAD-02/04/05/06/07; D4 soak LOAD-03, WEB, SHIP; D5 retest và kết luận.');
  xml = setCell(xml, 'F5', 'Đầu ra: k6 scripts/README; runtime config; raw metrics/summary; p95 theo endpoint/pha; timeline 1 phút; điểm gãy; ảnh Grafana; defect.');
  xml = setCell(xml, 'F8', 'Ngưỡng (R): API đọc p95 ≤ 500 ms; tìm kiếm ≤ 1 s, autocomplete ≤ 300 ms; 200 người dùng đồng thời; trang chủ ≤ 3 s; vị trí shipper ≤ 5 s; tìm bằng ảnh ≤ 3 s; chỉ mục ≤ 60 s. (Q): login, ghi giỏ, hủy đơn p95 ≤ 1 s; đặt đơn ≤ 2 s; lỗi < 1%; checks ≥ 99%.');
  xml = setCell(xml, 'A9', 'Ghi chú: testcase bám SRS v1.1 mục 6.1; cột Note ghi kỹ thuật và yêu cầu được kiểm chứng. P0/P1 là ưu tiên. Ca có service chưa triển khai ghi Pending kèm lý do "blocked", không đánh Passed. Mọi ca ban đầu Pending; timezone Asia/Bangkok (UTC+7).');
  const counta = ranges.map((g) => `COUNTA(A${g.first}:A${g.last})`).join(',');
  xml = setFormula(xml, 'B4', `SUM(${counta})`, total);
  [['6', 'F'], ['7', 'I'], ['8', 'L']].forEach(([row, col]) => {
    ['B', 'C', 'D', 'E'].forEach((c) => {
      const f = `SUM(${ranges.map((g) => `COUNTIFS($${col}$${g.first}:$${col}$${g.last},${c}$5)`).join(',')})`;
      xml = setFormula(xml, `${c}${row}`, f, c === 'D' ? total : 0);
    });
  });

  // Merges: keep the header block, add the function header rows.
  const keep = [...xml.match(/<mergeCells[\s\S]*?<\/mergeCells>/)[0].matchAll(/<mergeCell ref="([A-Z]+)(\d+):([A-Z]+)(\d+)"\/>/g)].filter((m) => Number(m[2]) < 11).map((m) => `${m[1]}${m[2]}:${m[3]}${m[4]}`);
  const merges = [...keep, ...ranges.flatMap((g) => [`A${g.header}:E${g.header}`, `F${g.header}:O${g.header}`])];
  xml = xml.replace(/<mergeCells[\s\S]*?<\/mergeCells>/, `<mergeCells count="${merges.length}">${merges.map((m) => `<mergeCell ref="${m}"/>`).join('')}</mergeCells>`);

  // Conditional formats (one rule set per status column) and the status drop-down.
  const cfs = [...xml.matchAll(/<conditionalFormatting sqref="([A-Z])\d+:[A-Z]\d+">([\s\S]*?)<\/conditionalFormatting>/g)];
  const rules = {};
  for (const m of cfs) rules[m[1]] = rules[m[1]] || m[2];
  let priority = 1;
  const cfXml = ['F', 'I', 'L']
    .flatMap((col) =>
      ranges.map((g) => `<conditionalFormatting sqref="${col}${g.first}:${col}${g.last}">${rules[col].replace(/priority="\d+"/g, () => `priority="${priority++}"`)}</conditionalFormatting>`),
    )
    .join('');
  xml = xml.replace(/(<conditionalFormatting[\s\S]*<\/conditionalFormatting>)/, cfXml);
  const sqref = ['F', 'I', 'L'].flatMap((col) => ranges.map((g) => `${col}${g.first}:${col}${g.last}`)).join(' ');
  xml = xml.replace(/(<dataValidation [^>]*sqref=")[^"]+(")/, `$1${sqref}$2`);
  setEntryText(sheet, xml);
  console.log(`Performance Test: ${total} test cases in rows ${ranges.map((g) => `${g.first}-${g.last}`).join(', ')}`);
  globalThis.__ranges = ranges;
  globalThis.__total = total;
}

// ---------------------------------------------------------------- Test Cases
{
  const sheet = part('Test Cases');
  let xml = entryText(sheet);
  const ranges = globalThis.__ranges;
  const names = ['Phản hồi API đọc và tìm kiếm', 'Tải 200 người dùng đồng thời', 'Trang chủ và vị trí giao hàng'];
  const ids = FUNCTIONS.map((f) => f.cases.map((c) => c.id));
  FUNCTIONS.forEach((fn, i) => {
    const row = 21 + i;
    xml = setCell(xml, `C${row}`, names[i]);
    xml = setCell(xml, `E${row}`, `${fn.trace}. Thực hiện ${fn.cases.length} testcase: ${ids[i].join(', ')}; chuẩn bị dữ liệu, chạy, lưu evidence và retest.`);
    xml = setCell(xml, `F${row}`, fn.common);
    xml = setFormula(xml, `I${row}`, `COUNTA('Performance Test'!A${ranges[i].first}:A${ranges[i].last})`, fn.cases.length);
    xml = setCell(xml, `J${row}`, 'k6 scripts/README; runtime config; raw metrics/summary; p95 theo endpoint/pha; timeline 1 phút; điểm gãy; bottleneck.');
  });
  const totalAll = 112 - 15 + globalThis.__total;
  xml = setFormula(xml, 'I24', 'SUM(I9:I23)', totalAll);

  const text = {
    D90: '850 BUYER riêng, ACTIVE/verified, có địa chỉ mặc định, chia nhóm: 400 mua sắm (giỏ 20 dòng thuộc 2 gian hàng, dòng đổi số lượng giá 100000), 400 đặt hàng, 50 người mua của đơn đang giao; 50 SHIPPER. Mỗi VU dùng 1 tài khoản; không dùng B1/B2 hoặc tài khoản người thật',
    D91: '≥ 1000 sản phẩm ACTIVE, 10 danh mục; 1 sản phẩm HIDDEN (PH) và 1 DRAFT (PD); 5 sản phẩm tranh chấp 50 đơn vị; lịch sử 2 đơn cho 200 buyer; 50 đơn đã phân công đang giao; ảnh truy vấn ≤ 10 MB. Số lượng ghi vào manifest (perf/fixtures/prepare.mjs)',
    D106: 'Công cụ: k6 (SRS NFR-PERF-03 nêu Artillery/k6) + Prometheus/Grafana (infra/monitoring/). Script trong perf/: mỗi testcase một kịch bản; threshold theo endpoint và pha giữ tải; business check và bất biến dữ liệu làm run fail khi sai dữ liệu.',
    D107: 'ENV-P: topology đầy đủ (gateway, auth, user, shop, product, cart, order, delivery, ai-search, recommendation) trên máy đạt SRS 5.2 (≥ 4 vCPU, 8 GB RAM), không dùng chung functional QA; k6 chạy trên máy khác. Ghi CPU/RAM/OS, giới hạn container/JVM, số instance từng service (cart một instance), build SHA, đường mạng (trực tiếp hay qua tunnel/Cloudflare), mức log.',
    D109: 'API đọc thông thường p95 ≤ 500 ms, áp riêng cho 13 API đọc của người mua (danh sách, lọc giá/đánh giá/gian hàng, sắp xếp, chi tiết + sản phẩm tương tự, cây danh mục, trang gian hàng, giỏ, lịch sử/chi tiết đơn, hồ sơ, sổ địa chỉ) trong pha giữ tải',
    D110: 'Ổn định ≥ 200 người dùng đồng thời: giữ đủ 200 VU chạy hành trình thực (duyệt, tìm, giỏ, đặt COD, tài khoản) 15 phút; soak 60 phút; spike 300 VU; stress tìm điểm gãy phải ≥ 200 VU. Ghi số VU, RPS và tài nguyên; 200 VU không đồng nghĩa 200 RPS',
    B111: 'NFR-PERF-02/04/05 (R)',
    D111: 'Tìm kiếm p95 ≤ 1 s, autocomplete ≤ 300 ms; trang chủ: LCP và nội dung chính p95 ≤ 3 s trên mạng 10/5 Mbit/s, 40 ms, desktop và 360 px; vị trí shipper tới người mua p95 ≤ 5 s; tìm bằng ảnh p95 ≤ 3 s; chỉ mục cập nhật ≤ 60 s (FR-SRCH-04)',
    D112: 'Login, ghi giỏ, kiểm tra trước thanh toán, hủy đơn p95 ≤ 1000 ms; đặt đơn COD p95 ≤ 2000 ms; lỗi HTTP ngoài dự kiến < 1%; business check ≥ 99%; endpoint chính ≥ 200 mẫu trong pha đo; không crash/OOM',
    D113: 'Không rò dữ liệu giữa user; giỏ khớp fixture; tổng tiền đơn = giá catalog × số lượng; không bán vượt tồn kho, reservedStock = tổng đơn còn hiệu lực; không lộ sản phẩm HIDDEN/DRAFT. Mọi vi phạm là Failed, không cho phép 1% sai tiền',
    D114: 'Spike và stress: trong 5 phút sau khi hạ tải trở về ngưỡng của mức 50 VU (chấm 3 phút cuối); heap, Redis, DB không tiếp tục tăng bất thường',
    D115: 'Tải vượt 200 VU (spike 300, stress tới 400) dùng chẩn đoán năng lực và biên an toàn; stress Failed nếu bậc cuối còn đạt < 200 VU. Data corruption/crash luôn ghi defect',
    D116: 'Rate limit khi kiểm thử hiệu năng',
    D117: 'Gateway giới hạn 200 request / 60 giây theo địa chỉ kết nối (SRS 5.3 không đặt ngưỡng hiệu năng); mọi VU phát từ một máy nên sẽ nhận 429 nếu không tắt.',
    D118: 'Capacity: chỉ trong ENV-P, đặt APP_RATE_LIMIT_ENABLED=false cho gateway, restart và xác minh cấu hình có hiệu lực; ghi vào báo cáo. 429 bất ngờ phải phân tích, không tự loại khỏi thống kê.',
    D119: 'Kiểm thử chính cơ chế giới hạn tần suất không phải yêu cầu hiệu năng: chuyển sang sheet API Test (200 request được qua, request 201 trả 429 RATE_LIMITED, mở lại sau cửa sổ mới).',
    D120: 'Gateway đếm theo địa chỉ kết nối trực tiếp: sau tunnel/proxy mọi người dùng chung một quota (defect PBL6-81). Chạy qua Cloudflare/tunnel phải thêm rule bỏ qua giới hạn cho IP máy k6.',
    D122: '1. Danh tính: buyer theo nhóm (400 mua sắm có giỏ fixture, 400 đặt hàng, 50 người mua đơn đang giao) và 50 shipper; một VU một tài khoản và cookie jar riêng (Auth và Cart khóa theo user). Phiên đăng nhập trước pha đo, refresh trước khi access token 15 phút hết hạn.',
    D123: '2. API đọc: danh sách 20%, chi tiết + tương tự 18%, theo danh mục 12%, giỏ 8%, lọc giá + sắp xếp giá 8%, lọc đánh giá + bán chạy 6%, sản phẩm theo gian hàng 6%, cây danh mục 5%, trang gian hàng 5%, lịch sử đơn 4%, chi tiết đơn 3%, hồ sơ 3%, sổ địa chỉ 2%; nghỉ 1–3 giây.',
    D124: '3. Tìm kiếm: gõ từ khóa (autocomplete từ ký tự thứ 2, 150–400 ms giữa phím), tìm, 30% thêm lọc danh mục/giá, 25% sang trang 2; từ khóa có dấu, không dấu, sai chính tả, đồng nghĩa. AI Search lỗi → tìm cơ bản ở Product Catalog (UC-03), tỷ lệ fallback ghi riêng.',
    D125: '4. Giỏ: GET → PATCH quantity 1/2 → GET, mỗi 3 lượt POST /api/cart/validate. Đặt hàng: xóa giỏ, thêm 1–3 dòng một gian hàng, kiểm tra, đặt COD có Idempotency-Key, gửi lại key, xem, hủy. Tài khoản: login → hồ sơ → địa chỉ → đơn → refresh → logout.',
    D126: '5. Tải phối hợp mỗi 200 VU: 70 duyệt (khách), 30 tìm kiếm, 50 giỏ, 20 đặt hàng COD, 30 tài khoản/theo dõi đơn; stress nhân theo tỷ lệ tới 400 VU. Chỉ COD; không gọi thanh toán PayOS, hoàn tiền hay đối soát.',
    D127: '6. Pha đo: warm-up 1 phút ở 10 VU, ramp 30 giây, sau đó giữ tải. Loại warm-up, ramp, đỉnh spike và các bậc stress khỏi ngưỡng acceptance, vẫn lưu raw metrics. Endpoint chính cần ≥ 200 mẫu trong pha đo; thiếu mẫu ghi Pending (inconclusive). Smoke không cần đạt số mẫu.',
    D128: '7. Đo phía người dùng: trang chủ đo trên Chromium thật, mỗi lần tải một phiên mới (cache lạnh), 20 lần mỗi màn hình; vị trí shipper đo đầu-cuối từ lúc shipper gửi tới lúc người mua nhận qua WebSocket (cùng đồng hồ máy k6); chỉ mục đo từ lúc tạo sản phẩm tới khi tìm thấy.',
    D130: '9. Dừng an toàn: lỗi ngoài dự kiến > 10% liên tục 60 giây, OOM hoặc sai dữ liệu → dừng và lưu evidence. Ca bị dừng, thiếu mẫu, service phụ thuộc chưa chạy (blocked) hoặc phải dùng fallback (degraded) ghi Pending kèm lý do, không đánh Passed.',
    D132: '1 VU mỗi hành trình × 1 phút (warm-up 15 giây), xác minh script và dữ liệu',
    D133: 'Warm-up 1 phút ở 10 VU, ramp 30 giây tới 50 VU, giữ 5 phút',
    D134: 'Ramp 30 giây tới 200 VU, giữ 10 phút (PERF-LOAD-02 giữ 15 phút)',
    D136: 'Ramp 30 giây tới 200 VU, giữ 60 phút',
    D137: 'Stress: 100 → 400 VU, mỗi bậc +50 VU giữ 3 phút, sau đó 5 phút ở 50 VU. Ngưỡng p95 áp riêng từng endpoint trong pha giữ tải, không lấy p95 tổng của cả run để che một endpoint chậm. Report bổ sung đồ thị theo cửa sổ 1 phút để nhận diện suy giảm.',
    D167: `0 — 0 — ${globalThis.__total} — 0 — ${globalThis.__total}`,
    D168: `0 — 0 — ${totalAll} — 0 — ${totalAll}`,
  };
  for (const [ref, value] of Object.entries(text)) xml = setCell(xml, ref, value);
  setEntryText(sheet, xml);
  globalThis.__totalAll = totalAll;
}

// ---------------------------------------------------------------- Statistics, Cover
{
  const stats = part('Test Statistics');
  let xml = entryText(stats);
  xml = setCell(xml, 'C6', `${globalThis.__totalAll} testcase / 15 chức năng. Kết quả khởi tạo Pending; App chờ build BLK-MOB-01.`);
  xml = setFormula(xml, 'H15', "'Performance Test'!B4", globalThis.__total);
  xml = setFormula(xml, 'H17', 'SUM(H11:H15)', globalThis.__totalAll);
  setEntryText(stats, xml);
  const cover = part('Cover');
  setEntryText(cover, setCell(entryText(cover), 'E11', `15 chức năng / ${globalThis.__totalAll} testcase; 5 mảng; kỹ thuật test; 3 vòng và thống kê.`));
}

const workbook = entries.find((e) => e.name === 'xl/workbook.xml');
let wb = entryText(workbook);
wb = /<calcPr[^>]*\/>/.test(wb) ? wb.replace(/<calcPr([^>]*?)\s*\/>/, (m, a) => `<calcPr${a.replace(/\sfullCalcOnLoad="[^"]*"/, '')} fullCalcOnLoad="1"/>`) : wb.replace('</workbook>', '<calcPr fullCalcOnLoad="1"/></workbook>');
setEntryText(workbook, wb);
writeFileSync(output, writeZip(entries));
console.log(`written: ${output}`);
