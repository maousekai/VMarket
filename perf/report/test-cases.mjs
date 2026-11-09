// The performance test cases (single source of truth): run.mjs executes them and
// rebuild-excel.mjs writes them into the "Performance Test" sheet.
// Requirement source: SRS v1.1 section 6.1 (NFR-PERF-01..05) plus the timing and
// consistency requirements they rely on (FR-SRCH-04, UC-06, NFR-REL-02, NFR-USA-03).

export const OWNER = 'Nguyễn Phương Đông';
export const REVIEWER = 'Nguyễn Khắc Hiếu';

const TECH = {
  LOAD: 'LOAD — giữ workload theo profile; kiểm tra p95 từng endpoint trong pha giữ tải, tỷ lệ lỗi, throughput và tài nguyên.',
  CT: 'CT — đối chiếu contract: status, schema, dữ liệu trả về và chủ sở hữu dữ liệu.',
  SPIKE: 'SPIKE — tăng tải đột ngột rồi giảm; chỉ chấm pha phục hồi, pha đỉnh là chẩn đoán.',
  SOAK: 'SOAK — giữ tải dài để phát hiện suy giảm, rò rỉ bộ nhớ/kết nối theo thời gian.',
  STRESS: 'STRESS — tăng bậc vượt yêu cầu tới điểm gãy; ghi bậc cuối còn đạt và khả năng phục hồi.',
  CONC: 'CONC — đồng thời trên cùng dữ liệu: không bán vượt tồn kho, không trùng đơn, tồn kho nhất quán.',
  RES: 'RES — suy giảm có kiểm soát: dừng service phụ khi đang tải, luồng cốt lõi vẫn đạt ngưỡng.',
  E2E: 'E2E — đo thời gian đầu-cuối từ hành động của một tác nhân tới khi tác nhân khác nhận được kết quả.',
  UX: 'UX — đo trên trình duyệt thật (LCP, nội dung chính) với mạng mô phỏng và hai kích thước màn hình.',
  ST: 'ST — kiểm tra trạng thái trước/sau và dọn dữ liệu sau khi chạy.',
};

const note = (codes, priority, source) =>
  [
    `Kỹ thuật: ${codes.join('/')}`,
    ...codes.map((c) => TECH[c]),
    `Ưu tiên: ${priority}. Nguồn: ${source}.`,
    'R = yêu cầu SRS; Q = ngưỡng đề xuất (Q chỉ áp cho thao tác ghi SRS không đặt ngưỡng).',
    'Actual/Evidence/Defect: điền khi chạy (perf/results/R<n>/<TC>/); không lưu token/cookie/OTP thô.',
  ].join('\n');

// ---------------------------------------------------------------------------
export const FUNCTIONS = [
  {
    title: 'Function 1: Phản hồi API đọc và tìm kiếm',
    trace: 'NFR-PERF-01, NFR-PERF-02, NFR-PERF-05 (tìm bằng ảnh), FR-SRCH-04',
    common: 'ENV-P (≥ 4 vCPU, 8 GB RAM — SRS 5.2); PERF-CATALOG (≥ 1000 sản phẩm ACTIVE, 10 danh mục, PH/PD); PERF-USERS; manifest fixture',
    cases: [
      {
        id: 'PERF-READ-01', priority: 'P1', title: 'Smoke API đọc',
        procedure: 'Warm-up 15 giây, 1 VU × 1 phút; luân phiên toàn bộ 13 API đọc của người mua (danh sách, lọc giá/đánh giá/gian hàng, sắp xếp, chi tiết, danh mục, trang gian hàng, giỏ, đơn hàng, hồ sơ, sổ địa chỉ)',
        expected: 'Mọi response 200, đúng schema; chỉ có sản phẩm ACTIVE, không lộ PH/PD; giỏ khớp fixture; lịch sử đơn đúng chủ sở hữu. Smoke chưa đủ mẫu, không dùng kết luận p95',
        own: '1 VU, buyer có giỏ và lịch sử đơn',
        tech: ['LOAD', 'CT'], source: 'SRS 6.1 NFR-PERF-01; FR-PROD-03/05/06, FR-SHOP-03, FR-CART-02, FR-ORD-03, FR-USER-01/02',
        run: { script: 'tc/read.js', profile: 'smoke' },
      },
      {
        id: 'PERF-READ-02', priority: 'P1', title: 'API đọc — baseline 50 người dùng',
        procedure: 'Warm-up 1 phút ở 10 VU, ramp 30 giây tới 50 VU, giữ 5 phút; tỷ lệ request theo lưu lượng thực (danh sách 20%, chi tiết 18%, danh mục 12% …)',
        expected: 'Từng API đọc p95 ≤ 500 ms (R) trong pha giữ tải; lỗi < 1%; checks ≥ 99%; báo số mẫu và RPS từng endpoint',
        own: '50 buyer riêng, mỗi buyer một giỏ fixture',
        tech: ['LOAD'], source: 'SRS 6.1 NFR-PERF-01',
        run: { script: 'tc/read.js', profile: 'baseline' },
      },
      {
        id: 'PERF-READ-03', priority: 'P0', title: 'API đọc — 200 người dùng đồng thời',
        procedure: 'Ramp 30 giây tới 200 VU, giữ 10 phút; cùng tỷ lệ request như PERF-READ-02',
        expected: 'Đủ 200 VU hoạt động; từng API đọc p95 ≤ 500 ms (R) trong pha giữ tải, không lấy p95 tổng che endpoint chậm; lỗi < 1%; không lộ sản phẩm không được bán; không sai dữ liệu giỏ',
        own: '200 buyer riêng; không seed khi chạy',
        tech: ['LOAD', 'CT'], source: 'SRS 6.1 NFR-PERF-01, NFR-PERF-03',
        run: { script: 'tc/read.js', profile: 'load' },
      },
      {
        id: 'PERF-SRCH-01', priority: 'P1', title: 'Tìm kiếm và autocomplete — 50 người dùng',
        procedure: 'Ramp tới 50 VU, giữ 5 phút; mỗi lượt gõ từ khóa (autocomplete từ ký tự thứ 2), tìm kiếm, đôi khi lọc giá/danh mục hoặc sang trang 2; từ khóa có dấu, không dấu, sai chính tả nhẹ, từ đồng nghĩa',
        expected: 'Tìm kiếm p95 ≤ 1 giây, autocomplete p95 ≤ 300 ms (R); kết quả do AI Search trả (không qua fallback); lỗi < 1%',
        own: 'ai-search-service chạy, chỉ mục đồng bộ từ PERF-CATALOG',
        tech: ['LOAD', 'CT'], source: 'SRS 6.1 NFR-PERF-02; FR-SRCH-01/02/03; UC-03',
        run: { script: 'tc/search.js', profile: 'baseline' },
      },
      {
        id: 'PERF-SRCH-02', priority: 'P0', title: 'Tìm kiếm và autocomplete — 200 người dùng',
        procedure: 'Ramp tới 200 VU, giữ 10 phút; cùng hành trình như PERF-SRCH-01',
        expected: 'Tìm kiếm p95 ≤ 1 giây, autocomplete p95 ≤ 300 ms (R) trong pha giữ tải; ≥ 99% lượt tìm có kết quả từ AI Search; lỗi < 1%',
        own: 'ai-search-service chạy; theo dõi CPU/heap Elasticsearch',
        tech: ['LOAD'], source: 'SRS 6.1 NFR-PERF-02, NFR-PERF-03',
        run: { script: 'tc/search.js', profile: 'load' },
      },
      {
        id: 'PERF-SRCH-03', priority: 'P1', title: 'Tìm kiếm bằng hình ảnh',
        procedure: '10 người dùng, mỗi người tải một ảnh mỗi 5–15 giây trong 5 phút (≈ 1 request/giây); ảnh sản phẩm thật và một ảnh lớn 6–9 MB (≤ 10 MB theo SRS)',
        expected: 'Tìm bằng ảnh p95 ≤ 3 giây (R); trả danh sách sản phẩm tương đồng; không bị từ chối vì bận (429); lỗi < 1%',
        own: 'CNN image search khả dụng (health.image = UP), embedding đã lập chỉ mục',
        tech: ['LOAD', 'CT'], source: 'SRS 6.1 NFR-PERF-05; FR-SRCH-05; UC-21',
        run: { script: 'tc/image-search.js', profile: 'baseline' },
      },
      {
        id: 'PERF-SRCH-04', priority: 'P1', title: 'Đồng bộ chỉ mục khi đang tải',
        procedure: '50 VU tìm kiếm liên tục 10 phút; seller tạo 20 sản phẩm "Đang bán" mới (mỗi 30 giây), mỗi sản phẩm có tên duy nhất; dò API tìm kiếm mỗi 2 giây tới khi thấy',
        expected: 'Mọi sản phẩm mới tìm thấy được trong ≤ 60 giây (R, lấy giá trị lớn nhất, không lấy trung bình); không sản phẩm nào bị bỏ sót; tìm kiếm nền vẫn đạt NFR-PERF-02',
        own: 'Tài khoản seller seed và SEED_PASSWORD',
        tech: ['E2E', 'LOAD'], source: 'SRS FR-SRCH-04, UC-11 bước 5',
        run: { script: 'tc/index-sync.js', profile: 'load' },
      },
    ],
  },
  {
    title: 'Function 2: Tải 200 người dùng đồng thời',
    trace: 'NFR-PERF-03, NFR-REL-02, FR-ORD-01, FR-PROD-02, UC-03/05/06/07',
    common: 'ENV-P (≥ 4 vCPU, 8 GB RAM — SRS 5.2), k6 trên máy riêng, APP_RATE_LIMIT_ENABLED=false (ghi vào báo cáo); PERF-USERS 800 buyer (400 mua sắm + 400 đặt hàng), 400 giỏ fixture; chỉ COD',
    cases: [
      {
        id: 'PERF-LOAD-01', priority: 'P1', title: 'Smoke hành trình hỗn hợp',
        procedure: '1 VU cho mỗi hành trình × 1 phút: duyệt (khách), tìm kiếm, giỏ hàng, đặt hàng COD, tài khoản/theo dõi đơn',
        expected: 'Mọi hành trình chạy hết, response đúng contract; đơn COD tạo đúng tổng tiền, gửi lại Idempotency-Key trả cùng đơn, hủy được; script đủ điều kiện chạy tải',
        own: '5 VU, tài khoản riêng từng hành trình',
        tech: ['LOAD', 'CT'], source: 'SRS 6.1 NFR-PERF-03; UC-02/03/04/05/06/07',
        run: { script: 'tc/load.js', profile: 'smoke' },
      },
      {
        id: 'PERF-LOAD-02', priority: 'P0', title: '200 người dùng đồng thời — 15 phút',
        procedure: 'Warm-up 1 phút, ramp 30 giây tới 200 VU, giữ 15 phút. Mỗi 200 VU: 70 duyệt (khách), 30 tìm kiếm, 50 giỏ hàng, 20 đặt hàng COD, 30 tài khoản/theo dõi đơn',
        expected: 'Giữ đủ 200 VU; API đọc p95 ≤ 500 ms, tìm kiếm ≤ 1 giây, autocomplete ≤ 300 ms (R); login và thao tác ghi đạt Q; lỗi < 1%; checks ≥ 99%; bất biến tiền giỏ/đơn 100%; báo VU, RPS và tài nguyên',
        own: 'Lượt chạy chính thức của NFR-PERF-03',
        tech: ['LOAD', 'CT'], source: 'SRS 6.1 NFR-PERF-03 (k6), NFR-PERF-01/02',
        run: { script: 'tc/load.js', profile: 'load15' },
      },
      {
        id: 'PERF-LOAD-03', priority: 'P0', title: 'Soak 200 người dùng — 60 phút',
        procedure: 'Cùng workload PERF-LOAD-02, giữ 200 VU trong 60 phút; đối chiếu p95/RPS theo cửa sổ 1 phút và heap, DB pool, Redis trên Grafana',
        expected: 'Pha giữ tải đạt ngưỡng như PERF-LOAD-02; không suy giảm kéo dài, không tăng bộ nhớ/kết nối không giải thích được; không sai dữ liệu',
        own: 'Dataset cố định; access token được refresh trong lúc chạy',
        tech: ['SOAK', 'ST'], source: 'SRS 6.1 NFR-PERF-03; NFR-REL-01',
        run: { script: 'tc/load.js', profile: 'soak' },
      },
      {
        id: 'PERF-LOAD-04', priority: 'P1', title: 'Spike 50 → 300 → 50',
        procedure: 'Ổn định 2 phút ở 50 VU, tăng lên 300 VU trong 10 giây, giữ 2 phút, hạ về 50 VU và quan sát 5 phút',
        expected: 'Trong 5 phút sau spike trở về ngưỡng p95/lỗi của mức 50 VU (chấm 3 phút cuối); không treo thread/kết nối, không mất dữ liệu; latency ở 300 VU chỉ để chẩn đoán',
        own: 'Máy phát tải còn dư tài nguyên',
        tech: ['SPIKE'], source: 'SRS 6.1 NFR-PERF-03',
        run: { script: 'tc/load.js', profile: 'spike' },
      },
      {
        id: 'PERF-LOAD-05', priority: 'P1', title: 'Stress tìm điểm gãy',
        procedure: 'Tăng bậc 100 → 400 VU, mỗi bậc 50 VU giữ 3 phút; sau đó hạ về 50 VU và quan sát 5 phút',
        expected: 'Ghi bậc cuối cùng còn đạt (mọi endpoint trong ngân sách p95, lỗi < 1%) — phải ≥ 200 VU; sau khi hạ tải hệ thống phục hồi về ngưỡng; không crash/OOM, không sai dữ liệu',
        own: 'Pool 400 buyer mỗi loại; máy k6 đủ mạnh cho 400 VU',
        tech: ['STRESS'], source: 'SRS 6.1 NFR-PERF-03 (biên an toàn trên 200 người dùng)',
        run: { script: 'tc/load.js', profile: 'stress' },
      },
      {
        id: 'PERF-LOAD-06', priority: 'P0', title: 'Đặt hàng đồng thời tranh chấp tồn kho',
        procedure: '100 buyer đặt đơn COD cùng lúc trong 5 phút vào 5 sản phẩm chỉ có 50 đơn vị mỗi loại (cầu ≫ cung); mỗi đơn có Idempotency-Key và gửi lại một lần',
        expected: 'Không bán vượt tồn kho; reservedStock = tổng số lượng đơn còn hiệu lực, availableStock = stock − reserved ≥ 0; đơn thiếu hàng bị từ chối hoặc hủy (UC-06 A4); gửi lại cùng key không tạo đơn trùng; hủy đơn hoàn kho về 0 trong 60 giây',
        own: '5 sản phẩm tranh chấp sạch (reserved = 0) trước khi chạy',
        tech: ['CONC', 'ST'], source: 'SRS FR-ORD-01, FR-PROD-02, UC-06 (A4, hậu điều kiện "tồn kho nhất quán")',
        run: { script: 'tc/contention.js', profile: 'baseline' },
      },
      {
        id: 'PERF-LOAD-07', priority: 'P1', title: 'Suy giảm có kiểm soát khi AI lỗi',
        procedure: 'Chạy workload PERF-LOAD-02 ở 200 VU, 10 phút; khi bắt đầu pha giữ tải, dừng ai-search-service và recommendation-service',
        expected: 'Duyệt, giỏ hàng, đặt hàng, tài khoản vẫn đạt ngưỡng; tìm kiếm chuyển sang tìm cơ bản ở Product Catalog (UC-03) và vẫn có kết quả ≥ 99%; không lỗi lan sang luồng mua hàng',
        own: 'ai-search-service chạy lúc bắt đầu; người chạy dừng service theo hướng dẫn trên màn hình',
        tech: ['RES', 'LOAD'], source: 'SRS NFR-REL-02; UC-03 luồng ngoại lệ',
        run: { script: 'tc/load.js', profile: 'load', env: { DEGRADE: 'true' } },
      },
    ],
  },
  {
    title: 'Function 3: Trang chủ và vị trí giao hàng',
    trace: 'NFR-PERF-04, NFR-PERF-05, NFR-USA-03, FR-SHIP-04/05, UC-23/24',
    common: 'ENV-P; Chromium trên máy k6; "mạng thông thường" mô phỏng 10 Mbit/s xuống, 5 Mbit/s lên, độ trễ 40 ms; PERF-DELIVERY 50 đơn đang giao',
    cases: [
      {
        id: 'PERF-WEB-01', priority: 'P0', title: 'Trang chủ hiển thị ≤ 3 giây',
        procedure: '20 lần tải trang chủ cho mỗi màn hình (desktop 1366 px, điện thoại 360 px), mỗi lần một phiên trình duyệt mới (cache lạnh), mạng mô phỏng',
        expected: 'LCP p95 ≤ 3 giây và thẻ sản phẩm đầu tiên (nội dung chính) hiển thị p95 ≤ 3 giây (R) ở cả hai màn hình; trang có sản phẩm ở mọi lần tải',
        own: 'Hệ thống không tải',
        tech: ['UX'], source: 'SRS 6.1 NFR-PERF-04; NFR-USA-03 (360 px)',
        run: { script: 'tc/web.js', profile: 'baseline' },
      },
      {
        id: 'PERF-WEB-02', priority: 'P1', title: 'Trang chủ ≤ 3 giây khi hệ thống chịu tải',
        procedure: 'Như PERF-WEB-01 trong lúc 200 VU chạy workload API đọc (PERF-READ-03) song song',
        expected: 'LCP và nội dung chính p95 ≤ 3 giây (R) ở cả hai màn hình dưới tải 200 người dùng; tải nền vẫn đạt NFR-PERF-01',
        own: 'Tải nền tc/read.js chạy song song',
        tech: ['UX', 'LOAD'], source: 'SRS 6.1 NFR-PERF-04, NFR-PERF-03',
        run: { script: 'tc/web.js', profile: 'baseline', companion: { script: 'tc/read.js', profile: 'load' } },
      },
      {
        id: 'PERF-SHIP-01', priority: 'P0', title: 'Vị trí shipper tới người mua ≤ 5 giây',
        procedure: '50 đơn đang giao: người mua mở WebSocket theo dõi từng đơn; shipper gửi tọa độ GPS mỗi 10–30 giây trong 10 phút',
        expected: 'Độ trễ từ lúc shipper gửi tới lúc người mua nhận đúng tọa độ p95 ≤ 5 giây (R); mất cập nhật < 1%; gửi vị trí lỗi < 1%; ≥ 200 mẫu',
        own: 'delivery-service chạy; 50 đơn đã phân công ở trạng thái đang giao',
        tech: ['E2E', 'LOAD'], source: 'SRS 6.1 NFR-PERF-05; FR-SHIP-04/05; UC-23/24',
        run: { script: 'tc/ship.js', profile: 'baseline' },
      },
      {
        id: 'PERF-SHIP-02', priority: 'P1', title: 'Vị trí shipper ≤ 5 giây khi hệ thống chịu tải',
        procedure: 'Như PERF-SHIP-01 (9 phút) trong lúc 200 VU chạy workload API đọc song song',
        expected: 'Độ trễ vị trí p95 ≤ 5 giây (R), mất cập nhật < 1% dưới tải 200 người dùng; tải nền vẫn đạt NFR-PERF-01',
        own: 'Tải nền tc/read.js chạy song song',
        tech: ['E2E', 'LOAD'], source: 'SRS 6.1 NFR-PERF-05, NFR-PERF-03',
        run: { script: 'tc/ship.js', profile: 'baseline', env: { SHIP_MINUTES: '9' }, companion: { script: 'tc/read.js', profile: 'load' } },
      },
    ],
  },
];

// Flat table for run.mjs
export const TEST_CASES = Object.fromEntries(FUNCTIONS.flatMap((f) => f.cases.map((c) => [c.id, c.run])));

// Excel cell texts
export function excelRow(fn, c) {
  return {
    id: c.id,
    description: `[${c.priority}] ${c.title}`,
    procedure: c.procedure,
    expected: c.expected,
    preconditions: `${fn.common}\nRiêng ca: ${c.own}`,
    note: note(c.tech, c.priority, c.source),
  };
}
