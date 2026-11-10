# VMarket — Unit Test Report (PBL6-79)

All three recorded rounds passed the same 25 logical cases, with 30 JUnit invocations per round and zero failures, errors or skips.

## Scope and provenance

- Requirements: FR-CART-01/02/03 and FR-PROD-02; Cart 8 cases, Checkout 9, Inventory 8.
- Executed: 7 November 2026, Asia/Bangkok; source SHA `d59a8cbb7c8b4055c4c350fa2bc7878741bbfa08`.
- Tester: TV01 (AI agent, OpenAI Codex GPT-6); **Awaiting TV04 review**.
- Java 17.0.12, Maven Wrapper 3.9.16, JUnit5/Mockito/AssertJ; mocked I/O; zero business services started.
- Rounds2–3 were explicitly requested reruns on the unchanged source and harness. They do not establish final acceptance or validation of later dev changes.
- [Input data](evidence/input-data.md): aliases, fixtures and all 25 datasets. [Manifest](evidence/manifest.json): exact sources, commands, timestamps, case/method mapping and hashes.
- The three executable Java test sources are in the normal service test directories; exact paths/hashes are in the manifest.

## Round summary

| Round | Passed | Failed | Pending | Skipped | Invocations |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 25/25 | 0 | 0 | 0 | 30 |
| 2 | 25/25 | 0 | 0 | 0 | 30 |
| 3 | 25/25 | 0 | 0 | 0 | 30 |

## Case results

| ID | Scenario | Expected outcome | Round1 | Round2 | Round3 |
| --- | --- | --- | --- | --- | --- |
| UT-CART-01 | [P1] Giỏ rỗng | groups rỗng; totalQuantity=0; totalAmount=0; không tự tạo item | [Passed](evidence/round-1/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) |
| UT-CART-02 | [P0] Thêm mới và TTL | 1 dòng; lineTotal/subtotal/totalAmount=200000; qty=2; SET kèm TTL 30 ngày trong cùng lời gọi, không tách EXPIRE | [Passed](evidence/round-1/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) |
| UT-CART-03 | [P0] Cộng cùng variant | Chỉ 1 dòng qty=5; đơn giá mới 110000; tổng=550000; giữ addedAt cũ | [Passed](evidence/round-1/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) |
| UT-CART-04 | [P1] Tách biến thể | 2 dòng trong cùng shop; totalQuantity=3; totalAmount=320000; không gộp V1/V2 | [Passed](evidence/round-1/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) |
| UT-CART-05 | [P0] Tách nhóm shop và số tiền | 2 nhóm; S1 subtotal=200000; S2=150000; tổng=350000; qty=5; dùng so sánh BigDecimal theo giá trị | [Passed](evidence/round-1/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) |
| UT-CART-06 | [P0] Biên cộng dồn 999 | (a) nhận quantity=999; (b) ApiException code CART_QUANTITY_LIMIT_EXCEEDED, status400; không ghi đè state của (b) | [Passed](evidence/round-1/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) |
| UT-CART-07 | [P1] Biên 50 dòng | Dòng50 nhận; dòng51 bị CART_ITEM_LIMIT_EXCEEDED/400; vẫn 50 dòng; cộng item cũ còn hợp lệ nếu chưa vượt999 | [Passed](evidence/round-1/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) |
| UT-CART-08 | [P0] Chống tràn số legacy | CART_QUANTITY_LIMIT_EXCEEDED/400; dữ liệu không bị ghi thành qty/tổng âm; không có set mới | [Passed](evidence/round-1/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.cart.service.TV01CartSheetTest.xml) |
| UT-CHK-01 | [P1] Giỏ rỗng | ok=false; CART_EMPTY; không gọi Product Catalog | [Passed](evidence/round-1/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) |
| UT-CHK-02 | [P0] Đủ tồn đúng biên | ok=true; issues=[]; không sửa giỏ | [Passed](evidence/round-1/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) |
| UT-CHK-03 | [P0] Giá khác nhưng còn hàng | ok=false; đúng issue PRICE_CHANGED, cartPrice=100000/currentPrice=110000; không OUT_OF_STOCK | [Passed](evidence/round-1/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) |
| UT-CHK-04 | [P0] Thiếu tồn một đơn vị | OUT_OF_STOCK; requestedQuantity=2/availableStock=1; không PRICE_CHANGED | [Passed](evidence/round-1/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) |
| UT-CHK-05 | [P0] Đồng thời đổi giá và thiếu hàng | Có cả PRICE_CHANGED và OUT_OF_STOCK trên đúng product/variant; không chỉ giữ một cảnh báo | [Passed](evidence/round-1/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) |
| UT-CHK-06 | [P1] Product hoặc variant không còn | Mỗi dataset trả NOT_FOUND; ok=false; không ném NPE | [Passed](evidence/round-1/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) |
| UT-CHK-07 | [P0] Shop mismatch | SHOP_CHANGED; ok=false; bỏ đánh giá giá/tồn của item sai shop | [Passed](evidence/round-1/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) |
| UT-CHK-08 | [P1] Dữ liệu thiếu giá hoặc stock | (a) PRODUCT_DATA_INVALID; (b) OUT_OF_STOCK do stock được coi0; không trả ok=true hoặc NPE | [Passed](evidence/round-1/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) |
| UT-CHK-09 | [P1] Catalog mất kết nối | ok=false; SERVICE_UNAVAILABLE; dừng kiểm tra sau lỗi hạ tầng đầu tiên; không gọi item kế tiếp | [Passed](evidence/round-1/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.cart.service.TV01CheckoutSheetTest.xml) |
| UT-STK-01 | [P0] Reserve hợp lệ | status RESERVED; stock=10,reserved=5; lưu reservation và publish StockReserved đúng payload | [Passed](evidence/round-1/TEST-com.vmarket.product.TV01InventorySheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.product.TV01InventorySheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.product.TV01InventorySheetTest.xml) |
| UT-STK-02 | [P0] Biên đủ hàng và thiếu 1 | (a) reserved=10; (b) INSUFFICIENT_STOCK/409; không save product/reservation và không publish cho (b) | [Passed](evidence/round-1/TEST-com.vmarket.product.TV01InventorySheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.product.TV01InventorySheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.product.TV01InventorySheetTest.xml) |
| UT-STK-03 | [P0] Reserve lặp cùng order | Trả kết quả cũ; không tăng reserved, không publish lần 2 | [Passed](evidence/round-1/TEST-com.vmarket.product.TV01InventorySheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.product.TV01InventorySheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.product.TV01InventorySheetTest.xml) |
| UT-STK-04 | [P1] Sản phẩm không được bán | Từ chối; không thay tồn/không publish; dataset category ẩn: INVENTORY_CONFLICT/409; variant deleted: VARIANT_NOT_FOUND/404 | [Passed](evidence/round-1/TEST-com.vmarket.product.TV01InventorySheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.product.TV01InventorySheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.product.TV01InventorySheetTest.xml) |
| UT-STK-05 | [P0] Confirm một lần | CONFIRMED; stock=7,reserved=0; soldCount tăng 3; không trừ hai lần trong cùng thao tác | [Passed](evidence/round-1/TEST-com.vmarket.product.TV01InventorySheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.product.TV01InventorySheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.product.TV01InventorySheetTest.xml) |
| UT-STK-06 | [P0] Confirm lặp | Vẫn stock=7,reserved=0,soldCount=3; không save/publish nghiệp vụ trùng | [Passed](evidence/round-1/TEST-com.vmarket.product.TV01InventorySheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.product.TV01InventorySheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.product.TV01InventorySheetTest.xml) |
| UT-STK-07 | [P0] Release reservation | stock=10,reserved=0; status RELEASED; không hoàn lặp/publish trùng | [Passed](evidence/round-1/TEST-com.vmarket.product.TV01InventorySheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.product.TV01InventorySheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.product.TV01InventorySheetTest.xml) |
| UT-STK-08 | [P0] Hủy đơn đã chốt | stock=10,reserved=0,soldCount=0; không âm tồn/đếm bán; lưu trạng thái và phát sự kiện phù hợp | [Passed](evidence/round-1/TEST-com.vmarket.product.TV01InventorySheetTest.xml) | [Passed](evidence/round-2/TEST-com.vmarket.product.TV01InventorySheetTest.xml) | [Passed](evidence/round-3/TEST-com.vmarket.product.TV01InventorySheetTest.xml) |

## Verification and limits

- Historical XMLs have all JVM system properties removed. Suite counters, testcase identities and fixture outputs are unchanged; [publication audit](evidence/audit.json) records the metadata checks and sanitized hashes.

- Every retained XML maps to the exact class, method and required dataset. All 90 invocations across three rounds passed; these represent 25 distinct logical cases.
- XML files include observed fixture/results output; expected exceptions and no-write/idempotency checks are defined in the Java tests.
- Round1 Function1 attempt1 executed zero tests because sandbox permissions blocked Mockito attachment. Its successful attempt2 is retained; the interruption is recorded in the manifest.
- The three TV01 Java hashes match the sources used in all three historical rounds. Publication checks validate mapping, links and hashes; the supplemental review run below is recorded separately from those rounds.
- Unit mocks do not prove real Redis TTL, Mongo transactions, RabbitMQ delivery, HTTP contracts, concurrency, or full SRS acceptance. InventoryInputValidator is mocked in the historical TV01 fixture; the supplemental suite below exercises the real Bean Validation implementation.
- TV04 review of all P0s and at least one negative and boundary case per function remains outstanding.

## Scoped review follow-up — 9 November 2026

Confirmed gaps were addressed in three existing unit-test classes. Application code, dependencies and the original 25-case matrix are unchanged. [Supplemental JUnit results](evidence/review/TEST-review-regression.xml) and the manifest record a fresh **86/86 passing invocations**, zero failures/errors/skips, on the PR base plus the recorded test-source changes.

| Suite | Invocations | Purpose |
| --- | ---: | --- |
| CartServiceTest | 18 | Existing update/remove/clear plus Redis read/write/delete failure, malformed JSON recovery and same-instance concurrent adds |
| InventoryServiceTest | 21 | Conflicting retry items, multi-product validation, duplicate merge/overflow; stronger existing pending-state/return assertions |
| InventoryInputValidatorTest | 17 | Real validator:14 invalid datasets, two positive quantity boundaries and valid duplicate input |
| TV01CartSheetTest | 9 | Original Cart regression |
| TV01CheckoutSheetTest | 11 | Original Checkout regression |
| TV01InventorySheetTest | 10 | Original Inventory regression |

The 30 TV01 invocations still represent the original 25 logical cases. The other 56 include existing regressions and review additions; they do not change historical round totals. Valid duplicate items are accepted by validation, then merged with checked addition in InventoryService.

Existing early confirm/release, deferred return, over-return and Cart update/remove/clear tests were reused and strengthened where needed. No duplicate TV01 scenarios were introduced.

**Infrastructure scope:** ProductInfrastructureIntegrationTest already contains `mongoTransactionRollsBackDomainAndOutboxTogether`, `concurrentReservationNeverDoubleReservesStock`, `competingOrdersCannotOversellOrLeaveAnOrphanReservation` and `outboxMarksPublishedOnlyAfterRabbitBrokerAckAndRouting`. These were not executed in this unit-only follow-up. Real Redis expiry, multi-instance concurrency and broker ordering/duplicate delivery remain integration limits, not claims of this PR.

Supplemental result publication omits system properties and console logs while preserving testcase identities, timings and outcomes. The original nine published XMLs and their recorded fixture outputs are unchanged.
