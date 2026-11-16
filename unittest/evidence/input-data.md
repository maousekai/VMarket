# Unit test input data

Source: UnitTest_Plan version 2; SHA256 `240dae057b1268fbf830d85230b3de2dfc319b8460cf25662bb3394dae1c7b04`.

### 4.1. Dataset chuẩn tự chứa — không cần tra nguồn khác

Các alias chỉ biểu thị dữ liệu giả trong bộ nhớ. Bảng dưới chọn literal ID ổn định để agent tạo fixture nhất quán, không phải ID tồn tại trên server. Không gọi đăng ký/login/OTP, không yêu cầu TV04 seed dữ liệu thật; quy tắc lấy ID từ server chỉ thuộc test tích hợp ngoài ENV-U.

| Alias | Literal ID dùng trong unit fixture | Dữ liệu mặc định |
| --- | --- | --- |
| B1 | `user-1` | Buyer giả; key Redis `cart:user-1`; không cần token/credential |
| S1 / S2 | `shop-1` / `shop-2` | Hai shop giả phân biệt; shop của P1/P2 tương ứng |
| C1 | `category-unit-1` | Category giả đang visible; Product.categoryVisible=true trừ ca ghi khác |
| P1 | `product-1` | Product ACTIVE, thuộc S1/C1; deletedAt=null, moderationRemoved=false, shopSuspended=false |
| V1 | `variant-1` | Thuộc P1; đỏ/M; giá100000 VND, stock10, reservedStock0, soldCount0, deleted=false |
| V2 | `variant-2` | Thuộc P1; xanh/L; giá120000 VND, stock5, reservedStock0, soldCount0, deleted=false |
| P2 / V3 | `product-2` / `variant-3` | Product ACTIVE thuộc S2; V3 giá50000 VND, stock20, reservedStock0, soldCount0 |
| O1 | `order-1` | Order giả; reservation mặc định absent. Khi seed reservation, items=[P1/V1×3] trừ dataset ghi khác |
| CART-A | Không phải ID; là nội dung giỏ B1 | P1/V1/S1×2,unitPrice100000 và P2/V3/S2×3,unitPrice50000; đúng2 lần add; subtotals200000/150000,qty5,total350000 |
| T0 | `2026-01-01T00:00:00Z` | Instant cố định để seed addedAt/updatedAt khi cần; không phải ngày thực thi |

Mặc định chỉ tạo các variant cần cho từng test. Riêng STK chỉ tạo V1 khi kiểm inventory, tránh biến thể khác ảnh hưởng derived fields. Số liệu trong testcase có ưu tiên cao hơn mặc định: ví dụ STK01 reserved2, STK02 reserved8, STK05 reserved3. `stock` là tồn vật lý; `reservedStock` là tạm giữ; available=stock−reservedStock. Cart unitPrice là snapshot trong giỏ, độc lập giá catalog hiện tại.

### 4.2. Dataset cụ thể cho các ca rút gọn

Đây là phần mở rộng các diễn đạt “như trên”, “giá đúng”, “CART-A” trong bảng gốc. Kết hợp với procedure/expected từng ID ở mục5 để thực thi mà không cần XLSX.

| ID | Fixture và thao tác cụ thể |
| --- | --- |
| UT-CART-01 | Map rỗng, không có key `cart:user-1`; getCart(B1) |
| UT-CART-02 | Giỏ rỗng; add P1/V1/S1 qty2 giá100000; getCart(B1) |
| UT-CART-03 | Giỏ mới chứa P1/V1/S1 qty2 giá100000; giữ addedAt trước act; add cùng tuple qty3 giá110000 |
| UT-CART-04 | Giỏ mới chứa P1/V1/S1 qty2 giá100000; add P1/V2/S1 qty1 giá120000 |
| UT-CART-05 | Giỏ rỗng; add hai item của CART-A đúng như mục4.1; đọc lại giỏ |
| UT-CART-06 | Hai dataset độc lập P1/V1/S1 giá100000: (a) seed998 rồi add1; (b) seed999 rồi add1. Dataset b chụp Map trước act và kiểm không ghi |
| UT-CART-07 | Seed49 tuple product/variant khác nhau, qty1 giá100000/shopS1; dùng tên `product-limit-001`…`product-limit-049`, variant tương ứng. Thêm tuple050 qty1; thử tuple051 qty1; cuối cùng cộng1 vào tuple001. Sau bước cuối vẫn50 dòng, tuple001 qty2, totalQuantity51 |
| UT-CART-08 | Serialize legacy P1/V1/S1 qty2147483647 giá100000,T0 vào Map; add cùng tuple qty1; không gọi getCart để suy ra tổng legacy trước act, chỉ so Map và no-SET |
| UT-CHK-01 | cartService.items(B1)=[]; catalog không có interaction |
| UT-CHK-02 | Một item P1/V1/S1 qty2 giá100000; catalog cùng P1/V1/S1 giá100000,stock2 |
| UT-CHK-03 | Một item P1/V1/S1 qty2 giá100000; catalog cùng P1/V1/S1 giá110000,stock10 |
| UT-CHK-04 | Một item P1/V1/S1 qty2 giá100000; catalog cùng P1/V1/S1 giá100000,stock1 |
| UT-CHK-05 | Một item P1/V1/S1 qty2 giá100000; catalog cùng P1/V1/S1 giá110000,stock1 |
| UT-CHK-06 | Hai dataset độc lập cùng item P1/V1/S1 qty2 giá100000: (a) findProduct(P1)=Optional.empty(); (b) ProductSnapshot P1/S1 chỉ chứa V2 giá120000,stock5, không chứaV1 |
| UT-CHK-07 | Item P1/V1/S1 qty2 giá100000; catalog ProductSnapshot P1 thuộcS2, V1 giá110000,stock1; chỉ SHOP_CHANGED |
| UT-CHK-08 | Hai dataset độc lập P1/V1/S1: (a) cart qty2 giá100000, catalog V1 price=null,stock10; (b) cart qty1 giá100000, catalog V1 price100000,stock=null |
| UT-CHK-09 | Items theo thứ tự P1/V1/S1 qty2 giá100000, rồi P2/V3/S2 qty3 giá50000; findProduct(P1) throw ProductCatalogUnavailableException; không gọi P2 |
| UT-STK-01 | Product/variant ACTIVE/visible, stock10,reserved2,sold0; O1 chưa có; reserve O1[P1/V1×3] |
| UT-STK-02 | Hai dataset độc lập stock10,reserved8,sold0,O1 mới: (a) reserve2; (b) reserve3 |
| UT-STK-03 | Seed O1 RESERVED với P1/V1×3, product stock10,reserved3,sold0; reserve lại đúng O1/items này |
| UT-STK-04 | Hai dataset độc lập stock10,reserved0,sold0,O1 mới, reserve1: (a) categoryVisible=false,V1.deleted=false; (b) categoryVisible=true,V1.deleted=true |
| UT-STK-05 | O1 RESERVED[P1/V1×3], product stock10,reserved3; product.soldCount=0 và variant.soldCount=0; confirm(O1) |
| UT-STK-06 | O1 CONFIRMED[P1/V1×3], stock7,reserved0; product.soldCount=3 và variant.soldCount=3; pending returns=[]; confirm(O1) lần tiếp theo |
| UT-STK-07 | O1 RESERVED[P1/V1×3], stock10,reserved3,cả soldCount0; release(O1), rồi release lại trên cùng reservation đã RELEASED |
| UT-STK-08 | O1 CONFIRMED[P1/V1×3], stock7,reserved0,cả soldCount3; release(O1); mong đợi RELEASED,stock10,reserved0,cả soldCount0 |

Fixture là mới cho từng ID và từng dataset (a)/(b). Các chuỗi literal trong bảng alias phải được dùng thống nhất trong request, mock lookup, reservation/event và assertion. Không coi “item như trên” là yêu cầu chạy TC trước rồi dùng lại state của nó.

## Supplemental unit-review datasets — 9 November 2026

These use the existing service-unit fixtures independently of the original 25 sheet cases.

| Area | Dataset / expected assertion |
| --- | --- |
| Cart Redis failure | Seed p1/red/shop-1 qty2 at100000; independently fail read/write/delete. CartStorageException retains cause; Map unchanged; no EXPIRE. |
| Cart malformed JSON | Seed `{invalid-json`; read is empty without writes; next add recovers a valid cart with qty2 and amount200000. |
| Cart same-instance concurrency | Seed qty1; two workers synchronize each of40 additions. One line, qty81, amount8100000. |
| Inventory order conflict | RESERVED order-1 holds product-1/variant-1 qty3; retry qty4 returns INVENTORY_CONFLICT/409 without writes/events. |
| Inventory multi-product shortage | First product stock10/reserved2 requests3; second stock1/reserved0 requests2. INSUFFICIENT_STOCK/409; both models unchanged; no writes/events. |
| Inventory duplicate merge | Quantities2+3 merge to5; stock10/reserved1 becomes reserved6; one saved/event item with quantity5. |
| Inventory overflow | Duplicate quantities Integer.MAX_VALUE and1 yield INVALID_INVENTORY_REQUEST/400 before repository/publisher access. |
| Existing pending states/returns | Early confirm: stock7/reserved0/sold3. Early release: no product access/events. Deferred return processed after confirm: stock9/reserved0/sold1; repeat confirm leaves values unchanged. Excess return: INVALID_RETURN_QUANTITY/409 without writes. |
| Real validator rejection | Fourteen datasets: null payload; null/empty/blank order; null/empty items; null/empty/blank product or variant; zero/negative quantity. INVALID_INVENTORY_REQUEST/400. |
| Real validator acceptance | Quantity1 and Integer.MAX_VALUE are individually valid. Valid duplicate items are accepted for service normalization; the service checks aggregate overflow. |
| Existing Cart operations | Existing update/remove/clear tests are rerun rather than duplicated in TV01. |

Real Mongo rollback/concurrent reservations and broker acknowledgement have existing infrastructure tests. Real Redis expiry, distributed concurrency and broker ordering/duplicate delivery are outside this unit-review run.
