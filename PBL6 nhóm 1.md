**ĐẠI HỌC ĐÀ NẴNG**

**TRƯỜNG ĐẠI HỌC BÁCH KHOA**

**KHOA CÔNG NGHỆ THÔNG TIN**

**TÀI LIỆU ĐẶC TẢ YÊU CẦU PHẦN MỀM**

(SOFTWARE REQUIREMENTS SPECIFICATION – SRS)

**NỀN TẢNG THƯƠNG MẠI ĐIỆN TỬ ĐA NGƯỜI BÁN**

**TÍCH HỢP TRÍ TUỆ NHÂN TẠO (VMARKET)**

Học phần: PBL6 – Dự án chuyên ngành Công nghệ phần mềm

Giảng viên hướng dẫn: TS. Lê Thị Mỹ Hạnh

Nhóm thực hiện: \[Tên nhóm – Danh sách thành viên\]

Lớp: \[Lớp học phần\]

*Đà Nẵng, 08/2026 – Phiên bản 1.1*

**LỊCH SỬ PHIÊN BẢN**

| Phiên bản | Ngày | Người thực hiện | Nội dung thay đổi |
| :---: | :---: | ----- | ----- |
| 1.0 | 11/08/2026 | Nhóm PBL6 | Phát hành lần đầu |
| 1.1 | 11/08/2026 | Nhóm PBL6 | Bổ sung vai trò Shipper và theo dõi vị trí đơn hàng; tìm kiếm sản phẩm bằng hình ảnh (CNN); quy trình trả hàng – hoàn tiền |

**MỤC LỤC**

# **1\. Giới thiệu**

## **1.1. Mục đích tài liệu**

Tài liệu này đặc tả đầy đủ các yêu cầu chức năng và phi chức năng của hệ thống VMarket – nền tảng thương mại điện tử đa người bán tích hợp trí tuệ nhân tạo, được xây dựng theo kiến trúc microservices. Tài liệu là cơ sở thống nhất giữa các thành viên trong nhóm về phạm vi và hành vi của hệ thống, đồng thời là căn cứ để thiết kế, lập trình, kiểm thử và nghiệm thu sản phẩm.

Đối tượng đọc: các thành viên nhóm phát triển, giảng viên hướng dẫn và hội đồng đánh giá học phần PBL6.

## **1.2. Phạm vi sản phẩm**

VMarket là sàn thương mại điện tử theo mô hình đa người bán (multi-vendor): nhiều người bán độc lập mở gian hàng, tự quản lý sản phẩm, tồn kho và đơn hàng của mình; người mua tìm kiếm, mua sắm trên web và ứng dụng di động. Trí tuệ nhân tạo được tích hợp vào ba điểm chạm cốt lõi: tìm kiếm thông minh, gợi ý sản phẩm cá nhân hóa và trợ lý hỗ trợ khách hàng (chatbot).

Hệ thống gồm ba sản phẩm bàn giao:

* Web Admin: giao diện quản trị dùng chung cho Quản trị viên nền tảng và Người bán, phân tách bằng cơ chế phân quyền theo vai trò (RBAC).

* Web end-user: website mua sắm dành cho người mua.

* Ứng dụng di động (Android/iOS): trải nghiệm mua sắm cho người mua; kèm phân hệ dành cho Người giao hàng (Shipper) để nhận đơn, cập nhật trạng thái và chia sẻ vị trí giao hàng.

Trong phạm vi (in-scope): đăng ký/đăng nhập, quản lý gian hàng, danh mục – sản phẩm – biến thể, giỏ hàng, đặt hàng, thanh toán trực tuyến qua PayOS và COD, giao hàng nội bộ với vai trò Shipper và theo dõi vị trí đơn hàng thời gian thực, trả hàng – hoàn tiền, đánh giá sản phẩm, thông báo, tìm kiếm AI theo từ khóa và theo hình ảnh, gợi ý AI, chatbot AI, quản trị – kiểm duyệt nền tảng, thống kê.

Ngoài phạm vi (out-of-scope): tích hợp hãng vận chuyển bên thứ ba (giao hàng do đội ngũ Shipper nội bộ của nền tảng đảm nhiệm), đa tiền tệ/thuế quốc tế, ví điện tử nội bộ, phiên bản native riêng cho từng hệ điều hành (dùng một mã nguồn đa nền tảng).

## **1.3. Định nghĩa, thuật ngữ và từ viết tắt**

| Thuật ngữ | Giải thích |
| ----- | ----- |
| SRS | Software Requirements Specification – Tài liệu đặc tả yêu cầu phần mềm. |
| Microservices | Kiến trúc phần mềm chia hệ thống thành các dịch vụ nhỏ, độc lập triển khai, giao tiếp qua mạng. |
| API Gateway | Cổng vào duy nhất của hệ thống; định tuyến, xác thực và giới hạn tần suất các yêu cầu tới các service. |
| Event Bus | Kênh truyền sự kiện bất đồng bộ giữa các service (sử dụng RabbitMQ). |
| JWT | JSON Web Token – chuẩn token dùng để xác thực và truyền thông tin phiên đăng nhập. |
| RBAC | Role-Based Access Control – phân quyền truy cập theo vai trò người dùng. |
| PayOS | Cổng thanh toán trực tuyến tại Việt Nam, cung cấp payment link, mã QR VietQR và webhook xác nhận giao dịch. |
| COD | Cash On Delivery – thanh toán bằng tiền mặt khi nhận hàng. |
| RAG | Retrieval-Augmented Generation – kỹ thuật cho phép mô hình ngôn ngữ trả lời dựa trên dữ liệu được truy xuất từ kho tri thức của hệ thống. |
| FCM | Firebase Cloud Messaging – dịch vụ gửi thông báo đẩy (push notification) cho ứng dụng di động. |
| DLQ | Dead-Letter Queue – hàng đợi chứa các thông điệp xử lý thất bại để tra soát. |
| CSDL | Cơ sở dữ liệu. |
| CNN | Convolutional Neural Network – mạng nơ-ron tích chập, dùng để trích xuất đặc trưng (embedding) hình ảnh phục vụ tìm kiếm sản phẩm bằng ảnh. |
| Guest / Buyer / Seller / Shipper / Admin | Khách vãng lai / Người mua / Người bán / Người giao hàng / Quản trị viên nền tảng. |

## **1.4. Tài liệu tham khảo**

* IEEE Std 830-1998, IEEE Recommended Practice for Software Requirements Specifications.

* Slide giới thiệu học phần PBL6 – Dự án chuyên ngành Công nghệ phần mềm, Khoa CNTT, Trường ĐH Bách khoa – ĐHĐN.

* Tài liệu tích hợp PayOS dành cho lập trình viên (payos.vn/docs).

* Tài liệu chính thức: Spring Boot (Java), FastAPI, Elasticsearch, RabbitMQ, PostgreSQL, MongoDB, Redis, Docker.

## **1.5. Bố cục tài liệu**

Phần 2 mô tả tổng quan sản phẩm và bối cảnh kiến trúc. Phần 3 đặc tả yêu cầu chức năng, tổ chức theo từng microservice. Phần 4 trình bày danh sách use case và đặc tả chi tiết các use case tiêu biểu. Phần 5 mô tả yêu cầu giao diện ngoài. Phần 6 nêu các yêu cầu phi chức năng. Phần 7 mô tả mô hình dữ liệu và ma trận phân quyền. Phần 8 là phụ lục.

# **2\. Mô tả tổng quan**

## **2.1. Bối cảnh sản phẩm và kiến trúc hệ thống**

VMarket là hệ thống mới, độc lập, được xây dựng theo kiến trúc microservices hướng sự kiện (event-driven). Ba ứng dụng khách (Web Admin, Web end-user, Mobile) giao tiếp với hệ thống duy nhất qua API Gateway. Phía sau gateway, hệ thống được chia thành hai nhóm dịch vụ:

* Nhóm dịch vụ nghiệp vụ lõi (**Spring Boot – Java**): Auth, User, Shop, Product Catalog, Cart, Order, Payment, Delivery, Review, Notification.

* Nhóm dịch vụ AI (**Python – FastAPI**): AI Search, Recommendation, AI Chatbot.

Các service giao tiếp đồng bộ qua REST (thông qua gateway hoặc gọi nội bộ) và bất đồng bộ qua Event Bus (RabbitMQ) cho các nghiệp vụ như đặt hàng, cập nhật tồn kho, đồng bộ chỉ mục tìm kiếm và thu thập hành vi người dùng. Mỗi service sở hữu kho dữ liệu riêng (database-per-service). Các hệ thống ngoài tương tác gồm: cổng thanh toán PayOS (payment link/QR và webhook), FCM (thông báo đẩy) và máy chủ SMTP (email giao dịch). Sơ đồ kiến trúc chi tiết được trình bày trong tài liệu thiết kế hệ thống.

## **2.2. Các chức năng chính của sản phẩm**

* Đăng ký, đăng nhập, quản lý hồ sơ và sổ địa chỉ; phân quyền theo bốn vai trò Guest – Buyer – Seller – Admin.

* Người bán đăng ký gian hàng (Admin phê duyệt), quản lý sản phẩm có biến thể, tồn kho, đơn hàng và thống kê doanh thu của gian hàng.

* Người mua duyệt danh mục, tìm kiếm thông minh, xem chi tiết sản phẩm, quản lý giỏ hàng, đặt hàng và thanh toán qua PayOS hoặc COD, theo dõi đơn, yêu cầu trả hàng – hoàn tiền, đánh giá sản phẩm.

* Tìm kiếm sản phẩm bằng hình ảnh: người dùng chụp hoặc tải ảnh lên, hệ thống dùng mô hình CNN trích xuất đặc trưng và trả về các sản phẩm tương đồng.

* Shipper nhận đơn được phân công, cập nhật trạng thái giao hàng và chia sẻ vị trí GPS; người mua theo dõi vị trí đơn hàng thời gian thực trên bản đồ.

* Gợi ý sản phẩm cá nhân hóa trên trang chủ và sản phẩm tương tự trên trang chi tiết.

* Chatbot AI hỗ trợ hỏi đáp về sản phẩm, chính sách và tra cứu trạng thái đơn hàng.

* Thông báo trong ứng dụng, email giao dịch và thông báo đẩy trên di động.

* Quản trị nền tảng: duyệt gian hàng, kiểm duyệt sản phẩm/đánh giá, quản lý người dùng, danh mục, xử lý khiếu nại và bảng điều khiển thống kê toàn sàn.

## **2.3. Lớp người dùng và đặc điểm**

| Lớp người dùng | Đặc điểm và quyền hạn chính |
| ----- | ----- |
| Khách (Guest) | Chưa đăng nhập. Được duyệt danh mục, tìm kiếm, xem chi tiết sản phẩm/gian hàng và hỏi đáp với chatbot ở mức thông tin chung. Muốn mua hàng phải đăng ký tài khoản. |
| Người mua (Buyer) | Tài khoản đã đăng ký. Có toàn bộ quyền của Guest; thêm giỏ hàng, đặt hàng, thanh toán, theo dõi/hủy đơn, đánh giá sản phẩm đã mua, nhận thông báo, đăng ký mở gian hàng. |
| Người bán (Seller) | Buyer đã đăng ký gian hàng và được Admin phê duyệt. Quản lý thông tin gian hàng, sản phẩm, tồn kho, xử lý đơn hàng thuộc gian hàng, phản hồi đánh giá, xem thống kê doanh thu. Thao tác trên Web Admin. |
| Người giao hàng (Shipper) | Tài khoản do Admin tạo và quản lý. Xem danh sách đơn được phân công, cập nhật trạng thái lấy hàng – đang giao – giao thành công/thất bại, xác nhận thu COD và chia sẻ vị trí GPS trong quá trình giao. Thao tác trên phân hệ Shipper của ứng dụng di động. |
| Quản trị viên (Admin) | Vận hành nền tảng: phê duyệt/đình chỉ gian hàng, kiểm duyệt sản phẩm và đánh giá, quản lý người dùng, quản lý và phân công shipper, quản lý danh mục, phân xử trả hàng – khiếu nại, xem thống kê toàn sàn. Thao tác trên Web Admin. |
| Hệ thống ngoài | PayOS (xác nhận thanh toán qua webhook), FCM (thông báo đẩy), SMTP (email). Là tác nhân phụ trong các use case liên quan. |

## **2.4. Môi trường vận hành**

* Máy chủ: triển khai bằng Docker/Docker Compose trên VPS hoặc dịch vụ đám mây.

* Máy trạm phát triển: Windows 10/11 64-bit; cài đặt Docker Desktop (WSL2 backend), JDK 17 LTS trở lên (Eclipse Temurin), Maven/Gradle, Node.js LTS, Git for Windows; IDE khuyến nghị: IntelliJ IDEA (BE), VS Code (FE/mobile).

* Trình duyệt hỗ trợ: hai phiên bản mới nhất của Chrome, Edge, Firefox, Safari; giao diện web đáp ứng (responsive).

* Thiết bị di động: Android 8.0 trở lên, iOS 13 trở lên; build/chạy thử Android trực tiếp trên máy Windows qua Android Studio; build iOS cần macOS hoặc dịch vụ build đám mây (ví dụ EAS Build) do hạn chế của môi trường Windows. Thiết bị của Shipper phải bật định vị GPS khi giao hàng.

* Hạ tầng dữ liệu: PostgreSQL, MongoDB, Redis, Elasticsearch, RabbitMQ chạy dưới dạng container.

## **2.5. Ràng buộc thiết kế và cài đặt**

* Kiến trúc bắt buộc là microservices với API Gateway và Event Bus; mỗi service có CSDL riêng, không truy cập trực tiếp CSDL của service khác.

* Dịch vụ nghiệp vụ lõi viết bằng Spring Boot (Java, Spring Web/Spring Data); dịch vụ AI viết bằng Python (FastAPI).

* Frontend web sử dụng React; ứng dụng di động sử dụng React Native.

* Phân hệ Shipper là một phần của ứng dụng di động (hiển thị theo vai trò đăng nhập), không phát triển ứng dụng riêng.

* Tìm kiếm bằng hình ảnh sử dụng mô hình CNN tiền huấn luyện để trích xuất embedding; embedding lưu và truy vấn tương đồng trên Elasticsearch (dense vector).

* Thanh toán trực tuyến tích hợp PayOS ở môi trường thử nghiệm (sandbox/test) trước khi chạy thật; bắt buộc xác minh chữ ký webhook.

* Toàn bộ API công khai qua HTTP; tài liệu API sinh tự động bằng OpenAPI/Swagger (springdoc-openapi cho các service Spring Boot).

* Công cụ kiểm thử: Postman(API test), Selenium(UI web), Appium (App UI),...

* Container hóa toàn bộ dịch vụ bằng **Docker**; điều phối cục bộ bằng Docker Compose.

* Quản lý mã nguồn bằng Git (GitHub), mỗi service một repository; tích hợp CI/CD bằng Jenkins (build, chạy test, đóng gói Docker image, triển khai) thay cho GitHub Actions.

* Quản lý công việc và tiến độ nhóm bằng Jira (cấu trúc Epic – Story – Task – Subtask, làm việc theo Sprint); tiến độ tuân theo kế hoạch học phần (tuần 5–17).

## **2.6. Giả định và phụ thuộc**

* Tài khoản PayOS (kênh thanh toán thử nghiệm), dự án Firebase và tài khoản SMTP được cấp và hoạt động ổn định.

* Dịch vụ mô hình ngôn ngữ (LLM API) dùng cho chatbot khả dụng trong suốt quá trình phát triển và bảo vệ.

* Giao hàng do đội ngũ Shipper nội bộ của nền tảng thực hiện; không tích hợp hãng vận chuyển bên thứ ba.

* Dịch vụ bản đồ (Google Maps API hoặc OpenStreetMap/Leaflet) khả dụng để hiển thị vị trí giao hàng.

* Dữ liệu mẫu (danh mục, sản phẩm, người dùng) được khởi tạo sẵn phục vụ demo và kiểm thử.

# **3\. Yêu cầu chức năng**

Yêu cầu chức năng được tổ chức theo từng microservice. Mỗi yêu cầu có mã định danh dạng FR-\<SERVICE\>-\<số thứ tự\> và mức ưu tiên: Cao (bắt buộc cho phiên bản bảo vệ), TB – Trung bình (nên có), Thấp (mở rộng nếu còn thời gian). Web Admin, Web end-user và Mobile là các ứng dụng khách tiêu thụ API của các service dưới đây; các yêu cầu đánh dấu \[Admin\] hoặc \[Seller\] được thể hiện trên Web Admin, yêu cầu đánh dấu \[Shipper\] thể hiện trên phân hệ Shipper của ứng dụng di động, các yêu cầu còn lại thể hiện trên Web end-user và Mobile.

## **3.1. Auth Service – Xác thực và phân quyền**

| Mã | Tên yêu cầu | Mô tả | Ưu tiên |
| ----- | ----- | ----- | :---: |
| FR-AUTH-01 | Đăng ký tài khoản | Cho phép đăng ký bằng email và mật khẩu; kiểm tra trùng email, độ mạnh mật khẩu; gửi email xác thực tài khoản (OTP hoặc liên kết kích hoạt). | Cao |
| FR-AUTH-02 | Đăng nhập | Xác thực email/mật khẩu; trả về cặp JWT access token (thời hạn ngắn) và refresh token; khóa tạm thời sau 5 lần đăng nhập sai liên tiếp. | Cao |
| FR-AUTH-03 | Đăng nhập Google | Cho phép đăng nhập/đăng ký nhanh bằng tài khoản Google (OAuth 2.0). | TB |
| FR-AUTH-04 | Quên mật khẩu | Gửi email chứa liên kết/OTP đặt lại mật khẩu có thời hạn. | Cao |
| FR-AUTH-05 | Phân quyền RBAC | Quản lý vai trò Guest, Buyer, Seller, Shipper, Admin; API Gateway xác thực JWT và các service kiểm tra quyền trên từng endpoint. | Cao |
| FR-AUTH-06 | Quản lý phiên | Làm mới access token bằng refresh token; thu hồi refresh token khi đăng xuất hoặc khi tài khoản bị khóa. | TB |

## **3.2. User Service – Hồ sơ người dùng**

| Mã | Tên yêu cầu | Mô tả | Ưu tiên |
| ----- | ----- | ----- | :---: |
| FR-USER-01 | Quản lý hồ sơ | Xem và cập nhật họ tên, ảnh đại diện, số điện thoại, ngày sinh, giới tính. | Cao |
| FR-USER-02 | Sổ địa chỉ | Thêm, sửa, xóa địa chỉ giao hàng; đặt một địa chỉ mặc định. | Cao |
| FR-USER-03 | Đổi mật khẩu | Đổi mật khẩu khi đã đăng nhập (yêu cầu nhập mật khẩu hiện tại). | Cao |
| FR-USER-04 | \[Admin\] Quản lý người dùng | Tìm kiếm, xem danh sách người dùng; khóa/mở khóa tài khoản vi phạm; xem lịch sử hoạt động cơ bản. | Cao |

## **3.3. Shop Service – Gian hàng**

| Mã | Tên yêu cầu | Mô tả | Ưu tiên |
| ----- | ----- | ----- | :---: |
| FR-SHOP-01 | Đăng ký gian hàng | Buyer đăng ký mở gian hàng với tên, mô tả, logo, thông tin liên hệ; hồ sơ ở trạng thái "Chờ duyệt". | Cao |
| FR-SHOP-02 | \[Seller\] Quản lý gian hàng | Cập nhật thông tin, ảnh bìa, chính sách của gian hàng. | Cao |
| FR-SHOP-03 | Trang gian hàng công khai | Người mua xem trang gian hàng: thông tin, danh sách sản phẩm, điểm đánh giá trung bình. | Cao |
| FR-SHOP-04 | \[Admin\] Duyệt gian hàng | Phê duyệt, từ chối (kèm lý do) hoặc đình chỉ gian hàng; phát sự kiện ShopApproved/ShopSuspended. | Cao |
| FR-SHOP-05 | \[Seller\] Thống kê gian hàng | Bảng điều khiển doanh thu, số đơn theo trạng thái, sản phẩm bán chạy theo khoảng thời gian. | TB |

## **3.4. Product Catalog Service – Danh mục và sản phẩm**

| Mã | Tên yêu cầu | Mô tả | Ưu tiên |
| ----- | ----- | ----- | :---: |
| FR-PROD-01 | \[Seller\] Quản lý sản phẩm | Thêm, sửa, xóa (ẩn) sản phẩm: tên, mô tả, hình ảnh (tối đa 9 ảnh), danh mục, thương hiệu, biến thể (màu sắc, kích cỡ…), giá và tồn kho theo từng biến thể; trạng thái Nháp/Đang bán/Ẩn. | Cao |
| FR-PROD-02 | Quản lý tồn kho | Tạm giữ tồn kho khi đặt hàng, trừ kho khi thanh toán thành công/xác nhận COD, hoàn kho khi hủy đơn – xử lý qua sự kiện StockReserved/StockReleased. | Cao |
| FR-PROD-03 | \[Admin\] Quản lý danh mục | Quản lý cây danh mục đa cấp (thêm, sửa, ẩn, sắp xếp). | Cao |
| FR-PROD-04 | \[Admin\] Kiểm duyệt sản phẩm | Gỡ/khôi phục sản phẩm vi phạm kèm lý do; thông báo cho người bán. | TB |
| FR-PROD-05 | Duyệt và lọc sản phẩm | Người mua xem danh sách sản phẩm theo danh mục; lọc theo khoảng giá, đánh giá, gian hàng; sắp xếp theo mới nhất, bán chạy, giá. | Cao |
| FR-PROD-06 | Trang chi tiết sản phẩm | Hiển thị ảnh, mô tả, biến thể, giá, tồn kho, điểm đánh giá, gian hàng và khu vực sản phẩm tương tự. | Cao |

## **3.5. Cart Service – Giỏ hàng**

| Mã | Tên yêu cầu | Mô tả | Ưu tiên |
| ----- | ----- | ----- | :---: |
| FR-CART-01 | Quản lý giỏ hàng | Thêm sản phẩm (theo biến thể) vào giỏ, cập nhật số lượng, xóa; giỏ lưu trên Redis và đồng bộ giữa web và mobile của cùng tài khoản. | Cao |
| FR-CART-02 | Nhóm giỏ theo gian hàng | Hiển thị giỏ hàng nhóm theo từng gian hàng, tính tạm tính cho từng nhóm và tổng cộng. | Cao |
| FR-CART-03 | Kiểm tra trước thanh toán | Khi vào trang thanh toán, kiểm tra lại giá và tồn kho mới nhất; cảnh báo nếu sản phẩm hết hàng hoặc thay đổi giá. | Cao |

## **3.6. Order Service – Đơn hàng**

| Mã | Tên yêu cầu | Mô tả | Ưu tiên |
| ----- | ----- | ----- | :---: |
| FR-ORD-01 | Tạo đơn hàng | Tạo đơn từ giỏ hàng, tự động tách thành các đơn con theo từng gian hàng; chọn địa chỉ giao và phương thức thanh toán (PayOS/COD); phát sự kiện OrderPlaced. | Cao |
| FR-ORD-02 | Vòng đời đơn hàng | Quản lý trạng thái: Chờ thanh toán → Chờ xác nhận → Đang chuẩn bị → Chờ giao (đã phân công shipper) → Đang giao → Đã giao → Hoàn thành; các nhánh Đã hủy, Trả hàng/Hoàn tiền. Lưu lịch sử chuyển trạng thái. Đơn "Đã giao" tự chuyển "Hoàn thành" sau 7 ngày nếu không có yêu cầu trả hàng. | Cao |
| FR-ORD-03 | Theo dõi và hủy đơn | Buyer xem lịch sử/chi tiết đơn, theo dõi trạng thái; được hủy đơn khi đơn chưa được xác nhận. | Cao |
| FR-ORD-04 | \[Seller\] Xử lý đơn hàng | Seller xác nhận đơn, chuẩn bị hàng và bàn giao cho bộ phận giao hàng; các trạng thái giao do Shipper cập nhật (Delivery Service đồng bộ về đơn). | Cao |
| FR-ORD-05 | \[Admin\] Giám sát và khiếu nại | Admin xem toàn bộ đơn hàng, tiếp nhận và xử lý khiếu nại/tranh chấp giữa hai bên. | TB |
| FR-ORD-06 | Yêu cầu trả hàng | Trong vòng 7 ngày kể từ khi đơn "Đã giao", Buyer tạo yêu cầu trả hàng cho sản phẩm lỗi, không đúng mô tả hoặc giao thiếu/sai, kèm lý do và tối đa 5 ảnh/video minh chứng; mỗi sản phẩm trong đơn chỉ yêu cầu một lần. | Cao |
| FR-ORD-07 | Xử lý trả hàng | Seller chấp nhận hoặc từ chối (kèm lý do) trong 48 giờ; quá hạn hoặc Buyer khiếu nại quyết định từ chối thì chuyển Admin phân xử. Yêu cầu được chấp nhận: đơn/sản phẩm chuyển trạng thái "Trả hàng", hàng được thu hồi, sau đó kích hoạt hoàn tiền (FR-PAY-05). Phát sự kiện ReturnRequested/ReturnResolved. | Cao |

## **3.7. Payment Service – Thanh toán (PayOS)**

| Mã | Tên yêu cầu | Mô tả | Ưu tiên |
| ----- | ----- | ----- | :---: |
| FR-PAY-01 | Tạo yêu cầu thanh toán | Với đơn chọn PayOS: tạo payment link/mã QR VietQR gắn mã đơn hàng và số tiền; trả về cho client hiển thị; thời hạn thanh toán 15 phút. | Cao |
| FR-PAY-02 | Xử lý webhook PayOS | Nhận webhook từ PayOS, xác minh chữ ký bằng checksum key; cập nhật giao dịch thành công/thất bại; phát sự kiện PaymentSucceeded/PaymentFailed; đảm bảo idempotent (webhook trùng không xử lý hai lần). | Cao |
| FR-PAY-03 | Thanh toán COD | Hỗ trợ đặt hàng thanh toán khi nhận hàng; đơn chuyển thẳng sang "Chờ xác nhận". | Cao |
| FR-PAY-04 | Quản lý giao dịch | Lưu lịch sử giao dịch; tự động hủy đơn và hoàn tồn kho khi hết hạn thanh toán; cho phép Buyer thanh toán lại trong thời hạn. | Cao |
| FR-PAY-05 | Hoàn tiền | Ghi nhận và theo dõi hoàn tiền cho đơn đã thanh toán bị hủy hoặc có yêu cầu trả hàng được chấp nhận; Admin xác nhận hoàn tiền (chuyển khoản thủ công, hệ thống lưu trạng thái Chờ hoàn → Đã hoàn). | Cao |

## **3.8. Delivery Service – Giao hàng và định vị**

| Mã | Tên yêu cầu | Mô tả | Ưu tiên |
| ----- | ----- | ----- | :---: |
| FR-SHIP-01 | \[Admin\] Quản lý shipper | Tạo, khóa/mở tài khoản shipper; xem danh sách, khu vực hoạt động và hiệu suất giao hàng (số đơn, tỷ lệ thành công). | Cao |
| FR-SHIP-02 | Phân công giao hàng | Khi Seller bàn giao hàng, hệ thống phân công đơn cho shipper theo khu vực (tự động, Admin có thể điều chỉnh thủ công); phát sự kiện DeliveryAssigned. | Cao |
| FR-SHIP-03 | \[Shipper\] Xử lý đơn giao | Shipper xem danh sách đơn được phân công (địa chỉ lấy/giao, tiền COD); cập nhật trạng thái Đã lấy hàng → Đang giao → Giao thành công / Giao thất bại (kèm lý do); xác nhận thu tiền COD. | Cao |
| FR-SHIP-04 | \[Shipper\] Chia sẻ vị trí GPS | Trong quá trình giao, ứng dụng shipper gửi tọa độ GPS định kỳ 10–30 giây/lần về Delivery Service; tự động dừng khi kết thúc đơn. | Cao |
| FR-SHIP-05 | Theo dõi vị trí đơn hàng | Buyer xem vị trí shipper thời gian thực trên bản đồ cùng trạng thái đơn (cập nhật qua WebSocket, độ trễ ≤ 5 giây); Seller và Admin xem được với đơn thuộc phạm vi của mình. | Cao |
| FR-SHIP-06 | Giao lại và hoàn về | Giao thất bại được giao lại tối đa 2 lần; quá số lần, đơn chuyển quy trình hoàn về gian hàng và xử lý hoàn tiền nếu đã thanh toán. | TB |
| FR-SHIP-07 | Lịch sử hành trình | Lưu vết hành trình và mốc thời gian các trạng thái phục vụ tra soát, xử lý khiếu nại. | TB |

## **3.9. Review Service – Đánh giá**

| Mã | Tên yêu cầu | Mô tả | Ưu tiên |
| ----- | ----- | ----- | :---: |
| FR-REV-01 | Viết đánh giá | Buyer đánh giá 1–5 sao kèm bình luận và tối đa 5 ảnh cho sản phẩm thuộc đơn "Hoàn thành"; mỗi sản phẩm trong đơn chỉ đánh giá một lần. | Cao |
| FR-REV-02 | Tổng hợp điểm | Tính và cập nhật điểm trung bình, số lượt đánh giá của sản phẩm và gian hàng; phát sự kiện ReviewCreated. | Cao |
| FR-REV-03 | \[Seller\] Phản hồi đánh giá | Seller trả lời công khai đánh giá của khách trong gian hàng mình. | TB |
| FR-REV-04 | \[Admin\] Kiểm duyệt đánh giá | Ẩn/gỡ đánh giá vi phạm; (mở rộng) cảnh báo đánh giá bất thường bằng mô hình AI. | Thấp |

## **3.10. Notification Service – Thông báo**

| Mã | Tên yêu cầu | Mô tả | Ưu tiên |
| ----- | ----- | ----- | :---: |
| FR-NOTI-01 | Thông báo trong ứng dụng | Lắng nghe sự kiện (đơn hàng, thanh toán, giao hàng, trả hàng, duyệt shop…) và tạo thông báo cho đúng người nhận; trung tâm thông báo có đánh dấu đã đọc. | Cao |
| FR-NOTI-02 | Email giao dịch | Gửi email xác thực tài khoản, xác nhận đặt hàng, thanh toán thành công, cập nhật trạng thái đơn. | Cao |
| FR-NOTI-03 | Thông báo đẩy di động | Gửi push notification qua FCM cho các sự kiện quan trọng của Buyer/Seller. | TB |

## **3.11. AI Search Service – Tìm kiếm thông minh**

| Mã | Tên yêu cầu | Mô tả | Ưu tiên |
| ----- | ----- | ----- | :---: |
| FR-SRCH-01 | Tìm kiếm toàn văn tiếng Việt | Tìm theo tên/mô tả sản phẩm; hỗ trợ tiếng Việt không dấu và chịu lỗi chính tả (fuzzy matching). | Cao |
| FR-SRCH-02 | Mở rộng truy vấn và xếp hạng | Áp dụng từ đồng nghĩa (ví dụ: "áo thun" – "áo phông"); xếp hạng kết quả kết hợp độ liên quan, mức bán chạy và điểm đánh giá; kết hợp bộ lọc giá/danh mục. | Cao |
| FR-SRCH-03 | Gợi ý từ khóa | Autocomplete gợi ý từ khóa/sản phẩm ngay khi người dùng gõ. | TB |
| FR-SRCH-04 | Đồng bộ chỉ mục | Lắng nghe sự kiện ProductCreated/Updated/Deleted để cập nhật chỉ mục Elasticsearch trong vòng tối đa 1 phút; đồng thời trích xuất và cập nhật embedding ảnh sản phẩm bằng mô hình CNN. | Cao |
| FR-SRCH-05 | Tìm kiếm bằng hình ảnh (CNN) | Người dùng tải lên hoặc chụp ảnh (≤ 10 MB); hệ thống dùng mô hình CNN trích xuất vector đặc trưng của ảnh truy vấn, tìm lân cận gần nhất trên chỉ mục embedding ảnh sản phẩm và trả về danh sách sản phẩm tương đồng, kết hợp được với bộ lọc giá/danh mục. | Cao |
| FR-SRCH-06 | Tìm kiếm ngữ nghĩa | (Mở rộng) Tìm kiếm theo ý định bằng vector embedding cho truy vấn mô tả tự nhiên. | Thấp |

## **3.12. Recommendation Service – Gợi ý sản phẩm**

| Mã | Tên yêu cầu | Mô tả | Ưu tiên |
| ----- | ----- | ----- | :---: |
| FR-REC-01 | Gợi ý cá nhân hóa | Khu vực "Dành cho bạn" trên trang chủ dựa trên lịch sử xem, thêm giỏ và mua của người dùng (kết hợp collaborative \+ content-based). | Cao |
| FR-REC-02 | Sản phẩm tương tự | Khu vực "Sản phẩm tương tự" trên trang chi tiết dựa trên đặc trưng nội dung (danh mục, tên, mô tả, giá). | Cao |
| FR-REC-03 | Thu thập hành vi | Ghi nhận sự kiện view, add-to-cart, purchase qua Event Bus làm dữ liệu huấn luyện. | Cao |
| FR-REC-04 | Xử lý cold-start | Người dùng mới/khách: gợi ý theo sản phẩm bán chạy và danh mục đang xem. | Cao |
| FR-REC-05 | Huấn luyện định kỳ | Huấn luyện lại mô hình theo lô (batch) hằng ngày; lưu phiên bản mô hình. | TB |

## **3.13. AI Chatbot Service – Trợ lý hỗ trợ**

| Mã | Tên yêu cầu | Mô tả | Ưu tiên |
| ----- | ----- | ----- | :---: |
| FR-BOT-01 | Hỏi đáp RAG | Chatbot trả lời câu hỏi về sản phẩm, chính sách mua hàng/đổi trả, hướng dẫn sử dụng dựa trên kho tri thức của nền tảng (catalog \+ FAQ) bằng kỹ thuật RAG; từ chối lịch sự khi ngoài phạm vi. | Cao |
| FR-BOT-02 | Tra cứu đơn hàng | Buyer đã đăng nhập hỏi trạng thái đơn của chính mình; chatbot truy vấn Order Service và trả lời (không lộ dữ liệu người khác). | Cao |
| FR-BOT-03 | Chuyển tiếp hỗ trợ | Khi không trả lời được hoặc người dùng yêu cầu, tạo phiếu hỗ trợ/hướng dẫn liên hệ người bán hoặc Admin. | TB |
| FR-BOT-04 | Lịch sử hội thoại | Lưu và cho phép xem lại hội thoại của người dùng đã đăng nhập. | TB |

# **4\. Mô hình use case**

## **4.1. Danh sách use case**

| Mã | Tên use case | Tác nhân | Sản phẩm |
| ----- | ----- | ----- | ----- |
| UC-01 | Đăng ký tài khoản | Guest | Web end-user, Mobile |
| UC-02 | Đăng nhập / đăng xuất | Buyer, Seller, Admin | Cả ba sản phẩm |
| UC-03 | Tìm kiếm sản phẩm (AI) | Guest, Buyer | Web end-user, Mobile |
| UC-04 | Xem chi tiết và sản phẩm tương tự | Guest, Buyer | Web end-user, Mobile |
| UC-05 | Quản lý giỏ hàng | Buyer | Web end-user, Mobile |
| UC-06 | Đặt hàng và thanh toán (PayOS/COD) | Buyer, PayOS | Web end-user, Mobile |
| UC-07 | Theo dõi / hủy đơn hàng | Buyer | Web end-user, Mobile |
| UC-08 | Đánh giá sản phẩm | Buyer | Web end-user, Mobile |
| UC-09 | Hỏi đáp với trợ lý AI | Guest, Buyer | Web end-user, Mobile |
| UC-10 | Đăng ký mở gian hàng | Buyer | Web end-user |
| UC-11 | Quản lý sản phẩm gian hàng | Seller | Web Admin |
| UC-12 | Xử lý đơn hàng gian hàng | Seller | Web Admin |
| UC-13 | Xem thống kê gian hàng | Seller | Web Admin |
| UC-14 | Phản hồi đánh giá | Seller | Web Admin |
| UC-15 | Duyệt gian hàng / kiểm duyệt sản phẩm | Admin | Web Admin |
| UC-16 | Quản lý người dùng | Admin | Web Admin |
| UC-17 | Quản lý danh mục | Admin | Web Admin |
| UC-18 | Xem thống kê toàn nền tảng | Admin | Web Admin |
| UC-19 | Xử lý khiếu nại đơn hàng | Admin, Buyer, Seller | Web Admin |
| UC-20 | Nhận thông báo | Buyer, Seller, Shipper | Cả ba sản phẩm |
| UC-21 | Tìm kiếm sản phẩm bằng hình ảnh | Guest, Buyer | Web end-user, Mobile |
| UC-22 | Yêu cầu trả hàng / hoàn tiền | Buyer, Seller, Admin | Web end-user, Mobile, Web Admin |
| UC-23 | Giao hàng và cập nhật vị trí | Shipper | Mobile (phân hệ Shipper) |
| UC-24 | Theo dõi vị trí đơn hàng | Buyer | Web end-user, Mobile |
| UC-25 | Quản lý và phân công shipper | Admin | Web Admin |

Dưới đây đặc tả chi tiết các use case tiêu biểu, đại diện cho các luồng nghiệp vụ quan trọng nhất của hệ thống.

## **4.2. UC-03: Tìm kiếm sản phẩm (AI)**

| Mục | Nội dung |
| ----- | ----- |
| **Tác nhân** | Guest, Buyer |
| **Mô tả** | Người dùng tìm sản phẩm bằng từ khóa; hệ thống trả về kết quả phù hợp kể cả khi từ khóa sai chính tả hoặc không dấu. |
| **Tiền điều kiện** | Chỉ mục tìm kiếm đã được đồng bộ từ Product Catalog Service. |
| **Luồng chính** | 1\) Người dùng nhập từ khóa vào ô tìm kiếm; hệ thống hiển thị gợi ý autocomplete. 2\) Người dùng xác nhận tìm kiếm; client gọi AI Search Service qua API Gateway. 3\) Service chuẩn hóa truy vấn (bỏ dấu, sửa lỗi chính tả, mở rộng từ đồng nghĩa) và truy vấn Elasticsearch. 4\) Hệ thống trả về danh sách sản phẩm xếp hạng theo độ liên quan kết hợp mức bán chạy và điểm đánh giá. 5\) Người dùng áp dụng bộ lọc (giá, danh mục, đánh giá) và sắp xếp; sự kiện tìm kiếm được ghi nhận cho Recommendation Service. |
| **Luồng thay thế / ngoại lệ** | Không có kết quả: hiển thị thông báo và gợi ý sản phẩm bán chạy thuộc danh mục gần nhất. Dịch vụ tìm kiếm lỗi: chuyển hướng sang tìm kiếm cơ bản theo tên tại Product Catalog Service. |
| **Hậu điều kiện** | Danh sách kết quả hiển thị; hành vi tìm kiếm được lưu phục vụ gợi ý. |

## **4.3. UC-06: Đặt hàng và thanh toán qua PayOS**

| Mục | Nội dung |
| ----- | ----- |
| **Tác nhân** | Buyer (chính), PayOS (phụ) |
| **Mô tả** | Người mua đặt hàng từ giỏ và thanh toán trực tuyến bằng mã QR/payment link của PayOS. |
| **Tiền điều kiện** | Buyer đã đăng nhập, giỏ hàng có ít nhất một sản phẩm còn hàng. |
| **Luồng chính** | 1\) Buyer chọn "Thanh toán"; hệ thống kiểm tra lại giá/tồn kho và hiển thị trang xác nhận, tách đơn theo từng gian hàng. 2\) Buyer chọn địa chỉ giao hàng, phương thức thanh toán PayOS và xác nhận đặt hàng. 3\) Order Service tạo đơn ở trạng thái "Chờ thanh toán", phát sự kiện OrderPlaced; Product Catalog Service tạm giữ tồn kho. 4\) Payment Service gọi API PayOS tạo payment link/mã QR (hạn 15 phút) và trả về cho client hiển thị. 5\) Buyer quét mã QR bằng ứng dụng ngân hàng và hoàn tất chuyển khoản. 6\) PayOS gửi webhook về Payment Service; service xác minh chữ ký, ghi nhận giao dịch thành công và phát sự kiện PaymentSucceeded. 7\) Order Service chuyển đơn sang "Chờ xác nhận"; Notification Service gửi thông báo cho Buyer và Seller; giỏ hàng được xóa các mặt hàng đã đặt. |
| **Luồng thay thế / ngoại lệ** | A1 – COD: bỏ qua bước 4–6, đơn chuyển thẳng sang "Chờ xác nhận". A2 – Hết hạn thanh toán: hệ thống hủy đơn, hoàn tồn kho, thông báo cho Buyer (được phép đặt lại). A3 – Webhook sai chữ ký hoặc trùng lặp: bỏ qua và ghi log cảnh báo. A4 – Tồn kho không đủ ở bước 1/3: báo lỗi và yêu cầu cập nhật giỏ hàng. |
| **Hậu điều kiện** | Đơn hàng và giao dịch được lưu vết đầy đủ; tồn kho nhất quán; các bên liên quan nhận được thông báo. |

## **4.4. UC-09: Hỏi đáp với trợ lý AI**

| Mục | Nội dung |
| ----- | ----- |
| **Tác nhân** | Guest, Buyer |
| **Mô tả** | Người dùng trò chuyện với chatbot để hỏi về sản phẩm, chính sách hoặc trạng thái đơn hàng của mình. |
| **Tiền điều kiện** | Kho tri thức (catalog, FAQ, chính sách) đã được lập chỉ mục. Tra cứu đơn hàng yêu cầu đã đăng nhập. |
| **Luồng chính** | 1\) Người dùng mở cửa sổ chat và đặt câu hỏi. 2\) Chatbot Service truy xuất các đoạn tri thức liên quan (RAG) và sinh câu trả lời kèm nguồn tham chiếu nội bộ. 3\) Nếu câu hỏi liên quan đơn hàng và người dùng đã đăng nhập, service truy vấn Order Service theo đúng danh tính người hỏi rồi trả lời. 4\) Hội thoại được lưu vào lịch sử của người dùng (nếu đã đăng nhập). |
| **Luồng thay thế / ngoại lệ** | Câu hỏi ngoài phạm vi: chatbot từ chối lịch sự và đề xuất liên hệ hỗ trợ. Người dùng chưa đăng nhập hỏi về đơn hàng: chatbot yêu cầu đăng nhập. Dịch vụ LLM lỗi: hiển thị thông báo thử lại sau. |
| **Hậu điều kiện** | Người dùng nhận câu trả lời; hội thoại được lưu (nếu áp dụng). |

## **4.5. UC-11: Quản lý sản phẩm gian hàng**

| Mục | Nội dung |
| ----- | ----- |
| **Tác nhân** | Seller |
| **Mô tả** | Người bán tạo mới hoặc cập nhật sản phẩm có nhiều biến thể trong gian hàng của mình trên Web Admin. |
| **Tiền điều kiện** | Seller đã đăng nhập và gian hàng ở trạng thái "Hoạt động". |
| **Luồng chính** | 1\) Seller chọn "Thêm sản phẩm" và nhập tên, mô tả, danh mục, tải lên hình ảnh. 2\) Seller khai báo các biến thể (ví dụ màu sắc, kích cỡ) cùng giá và tồn kho cho từng biến thể. 3\) Seller lưu ở trạng thái "Nháp" hoặc "Đang bán"; hệ thống kiểm tra dữ liệu hợp lệ. 4\) Product Catalog Service lưu sản phẩm và phát sự kiện ProductCreated/ProductUpdated. 5\) AI Search Service cập nhật chỉ mục; sản phẩm "Đang bán" xuất hiện ở phía người mua trong vòng 1 phút. |
| **Luồng thay thế / ngoại lệ** | Dữ liệu không hợp lệ (thiếu ảnh, giá âm, chưa có biến thể): hiển thị lỗi tại chỗ. Sản phẩm bị Admin gỡ trước đó: chỉ được gửi lại sau khi chỉnh sửa. |
| **Hậu điều kiện** | Sản phẩm được lưu, đồng bộ chỉ mục tìm kiếm và sẵn sàng bán (nếu chọn "Đang bán"). |

## **4.6. UC-15: Duyệt gian hàng**

| Mục | Nội dung |
| ----- | ----- |
| **Tác nhân** | Admin |
| **Mô tả** | Quản trị viên xem xét và phê duyệt hồ sơ đăng ký gian hàng của người dùng. |
| **Tiền điều kiện** | Tồn tại hồ sơ gian hàng ở trạng thái "Chờ duyệt". |
| **Luồng chính** | 1\) Admin mở danh sách gian hàng chờ duyệt trên Web Admin. 2\) Admin xem chi tiết hồ sơ (thông tin gian hàng, chủ gian hàng). 3\) Admin chọn "Phê duyệt"; Shop Service chuyển trạng thái "Hoạt động", cấp vai trò Seller cho chủ gian hàng và phát sự kiện ShopApproved. 4\) Notification Service gửi thông báo và email kết quả cho người đăng ký. |
| **Luồng thay thế / ngoại lệ** | Từ chối: Admin nhập lý do, hồ sơ chuyển "Bị từ chối", người dùng có thể chỉnh sửa và gửi lại. Đình chỉ gian hàng đang hoạt động: toàn bộ sản phẩm bị ẩn khỏi phía người mua. |
| **Hậu điều kiện** | Trạng thái gian hàng và vai trò người dùng được cập nhật nhất quán; các bên nhận thông báo. |

## **4.7. UC-21: Tìm kiếm sản phẩm bằng hình ảnh**

| Mục | Nội dung |
| ----- | ----- |
| **Tác nhân** | Guest, Buyer |
| **Mô tả** | Người dùng tìm sản phẩm bằng cách chụp hoặc tải lên một tấm ảnh; hệ thống trả về các sản phẩm có hình ảnh tương đồng. |
| **Tiền điều kiện** | Embedding ảnh của các sản phẩm đang bán đã được lập chỉ mục (FR-SRCH-04). |
| **Luồng chính** | 1\) Người dùng chọn biểu tượng camera trong ô tìm kiếm; chụp ảnh (mobile) hoặc tải ảnh lên (web). 2\) Client gửi ảnh tới AI Search Service qua API Gateway. 3\) Mô hình CNN trích xuất vector đặc trưng của ảnh truy vấn. 4\) Hệ thống tìm lân cận gần nhất trên chỉ mục embedding và trả về danh sách sản phẩm tương đồng, sắp xếp theo độ tương đồng. 5\) Người dùng áp dụng thêm bộ lọc giá/danh mục như tìm kiếm thông thường. |
| **Luồng thay thế / ngoại lệ** | Ảnh không hợp lệ hoặc vượt 10 MB: báo lỗi và yêu cầu chọn lại. Không có kết quả đạt ngưỡng tương đồng: thông báo và gợi ý sản phẩm bán chạy. |
| **Hậu điều kiện** | Danh sách sản phẩm tương đồng được hiển thị; sự kiện tìm kiếm được ghi nhận cho gợi ý. |

## **4.8. UC-22: Yêu cầu trả hàng / hoàn tiền**

| Mục | Nội dung |
| ----- | ----- |
| **Tác nhân** | Buyer (chính), Seller, Admin (phụ) |
| **Mô tả** | Người mua khiếu nại sản phẩm lỗi hoặc không đúng mô tả và yêu cầu trả hàng – hoàn tiền; người bán xử lý, Admin phân xử khi có tranh chấp. |
| **Tiền điều kiện** | Đơn hàng ở trạng thái "Đã giao" trong vòng 7 ngày; sản phẩm chưa từng có yêu cầu trả hàng. |
| **Luồng chính** | 1\) Buyer mở chi tiết đơn, chọn "Yêu cầu trả hàng", chọn sản phẩm, lý do (lỗi, không đúng mô tả, giao thiếu/sai…) và tải lên tối đa 5 ảnh/video minh chứng. 2\) Hệ thống tạo yêu cầu ở trạng thái "Chờ người bán xử lý", phát sự kiện ReturnRequested và thông báo cho Seller. 3\) Seller xem minh chứng và chấp nhận yêu cầu trong vòng 48 giờ. 4\) Sản phẩm/đơn chuyển trạng thái "Trả hàng"; shipper được phân công thu hồi hàng về gian hàng; Seller xác nhận đã nhận lại hàng. 5\) Payment Service ghi nhận hoàn tiền (đơn đã thanh toán PayOS); Admin xác nhận đã chuyển hoàn; yêu cầu chuyển "Đã hoàn tiền" và thông báo các bên. |
| **Luồng thay thế / ngoại lệ** | A1 – Seller từ chối (kèm lý do): Buyer được khiếu nại lên Admin; Admin xem minh chứng hai bên và ra quyết định cuối cùng. A2 – Seller không phản hồi sau 48 giờ: yêu cầu tự động chuyển Admin xử lý. A3 – Quá 7 ngày kể từ khi "Đã giao": hệ thống không cho tạo yêu cầu. A4 – Đơn COD: bỏ bước hoàn tiền qua PayOS, chỉ hoàn trạng thái và tiền mặt do hai bên/Admin xác nhận. |
| **Hậu điều kiện** | Trạng thái trả hàng – hoàn tiền được lưu vết đầy đủ; thống kê tỷ lệ trả hàng của gian hàng được cập nhật. |

## **4.9. UC-23 & UC-24: Giao hàng và theo dõi vị trí đơn hàng**

| Mục | Nội dung |
| ----- | ----- |
| **Tác nhân** | Shipper, Buyer (chính); dịch vụ bản đồ (phụ) |
| **Mô tả** | Shipper thực hiện giao đơn được phân công và chia sẻ vị trí GPS; người mua theo dõi vị trí đơn hàng thời gian thực trên bản đồ. |
| **Tiền điều kiện** | Đơn đã được phân công cho shipper (DeliveryAssigned); thiết bị shipper bật GPS. |
| **Luồng chính** | 1\) Shipper đăng nhập phân hệ Shipper trên ứng dụng di động, xem danh sách đơn được phân công (địa chỉ lấy hàng, địa chỉ giao, tiền COD nếu có). 2\) Shipper đến gian hàng nhận hàng và cập nhật "Đã lấy hàng"; đơn chuyển trạng thái "Đang giao". 3\) Ứng dụng shipper gửi tọa độ GPS về Delivery Service định kỳ 10–30 giây/lần. 4\) Buyer mở chi tiết đơn, chọn "Theo dõi đơn hàng": bản đồ hiển thị vị trí shipper, cập nhật thời gian thực qua WebSocket (độ trễ ≤ 5 giây). 5\) Shipper giao hàng, thu COD (nếu có) và cập nhật "Giao thành công"; đơn chuyển "Đã giao", việc chia sẻ vị trí tự động dừng; Notification thông báo cho Buyer và Seller. |
| **Luồng thay thế / ngoại lệ** | A1 – Giao thất bại (khách không nhận, không liên lạc được): shipper ghi lý do; hệ thống cho giao lại tối đa 2 lần, quá số lần đơn chuyển quy trình hoàn về gian hàng. A2 – Mất kết nối GPS/mạng: bản đồ hiển thị vị trí cuối cùng kèm thời điểm cập nhật. A3 – Buyer không mở theo dõi: các mốc trạng thái vẫn được thông báo bình thường. |
| **Hậu điều kiện** | Hành trình và mốc thời gian được lưu (FR-SHIP-07); trạng thái đơn đồng bộ nhất quán giữa Delivery Service và Order Service. |

# **5\. Yêu cầu giao diện ngoài**

## **5.1. Giao diện người dùng**

* Web end-user (React): trang chủ (banner, danh mục, khu gợi ý "Dành cho bạn"), tìm kiếm từ khóa và tìm kiếm bằng hình ảnh, kết quả tìm kiếm và bộ lọc, chi tiết sản phẩm, trang gian hàng, giỏ hàng, thanh toán, quản lý đơn hàng kèm bản đồ theo dõi vị trí giao, yêu cầu trả hàng, hồ sơ cá nhân, trung tâm thông báo, cửa sổ chatbot.

* Web Admin (React): đăng nhập; phân hệ Seller (bảng điều khiển, quản lý sản phẩm, đơn hàng, xử lý yêu cầu trả hàng, đánh giá, thống kê gian hàng); phân hệ Admin (duyệt gian hàng, kiểm duyệt sản phẩm/đánh giá, quản lý người dùng, quản lý và phân công shipper, danh mục, phân xử trả hàng – khiếu nại, thống kê toàn sàn). Menu hiển thị theo vai trò.

* Mobile (React Native/Flutter): các màn hình tương ứng Web end-user, bổ sung tìm kiếm bằng camera và thông báo đẩy; phân hệ Shipper (danh sách đơn được phân công, cập nhật trạng thái giao, chia sẻ vị trí GPS); điều hướng thanh tab chuẩn di động.

* Toàn bộ giao diện bằng tiếng Việt, thiết kế đáp ứng; thông báo lỗi rõ ràng, thân thiện.

## **5.2. Giao diện phần cứng**

Hệ thống không giao tiếp trực tiếp với phần cứng chuyên dụng. Máy chủ triển khai trên VPS/cloud đạt tối thiểu 4 vCPU, 8 GB RAM cho môi trường demo; thiết bị người dùng là máy tính và điện thoại thông minh phổ thông có kết nối Internet.

## **5.3. Giao diện phần mềm**

| Thành phần | Mô tả giao tiếp |
| ----- | ----- |
| API Gateway | Điểm vào duy nhất cho ba ứng dụng khách; định tuyến REST/JSON tới các service; xác thực JWT; giới hạn tần suất; tài liệu OpenAPI/Swagger cho từng service. |
| PayOS | Payment Service gọi REST API của PayOS để tạo/hủy payment link; nhận webhook xác nhận giao dịch qua HTTPS, xác minh chữ ký bằng checksum key; sử dụng SDK Java chính thức của PayOS (hoặc gọi REST API trực tiếp). |
| RabbitMQ (Event Bus) | Các service phát/nhận sự kiện nghiệp vụ theo cơ chế publish/subscribe; cấu hình retry và DLQ cho thông điệp lỗi. |
| Elasticsearch | AI Search Service đọc/ghi chỉ mục sản phẩm; các service khác không truy cập trực tiếp. |
| FCM | Notification Service gửi thông báo đẩy tới thiết bị di động qua Firebase Cloud Messaging. |
| SMTP | Notification Service gửi email giao dịch qua máy chủ SMTP (ví dụ Gmail SMTP cho môi trường demo). |
| LLM API | Chatbot Service gọi dịch vụ mô hình ngôn ngữ bên ngoài để sinh câu trả lời trên ngữ cảnh đã truy xuất. |
| Dịch vụ bản đồ | Client hiển thị bản đồ và vị trí shipper bằng Google Maps API hoặc OpenStreetMap/Leaflet; Delivery Service chỉ lưu trữ và phát tọa độ, không phụ thuộc nhà cung cấp bản đồ cụ thể. |
| Mô hình CNN | AI Search Service chạy nội bộ mô hình CNN tiền huấn luyện (ResNet/MobileNet) để trích xuất embedding ảnh; không gọi dịch vụ ngoài, bảo đảm ảnh người dùng không rời hệ thống. |

## **5.4. Giao diện truyền thông**

* Client ↔ hệ thống: HTTPS (TLS 1.2+), REST/JSON; xác thực bằng Bearer JWT.

* Chatbot: kết nối thời gian thực qua WebSocket/SignalR (hoặc HTTP streaming).

* Nội bộ giữa các service: HTTP/REST trong mạng Docker và AMQP (RabbitMQ) cho sự kiện.

* Theo dõi vị trí: ứng dụng Shipper gửi tọa độ qua HTTPS/WebSocket; client người mua nhận cập nhật vị trí qua WebSocket theo từng đơn hàng.

* Webhook PayOS: HTTPS POST tới endpoint công khai của Payment Service.

# **6\. Yêu cầu phi chức năng**

## **6.1. Hiệu năng**

* NFR-PERF-01: Thời gian phản hồi API đọc thông thường ≤ 500 ms (p95) trong điều kiện demo.

* NFR-PERF-02: Tìm kiếm trả kết quả ≤ 1 giây; gợi ý autocomplete ≤ 300 ms.

* NFR-PERF-03: Hệ thống phục vụ ổn định tối thiểu 200 người dùng đồng thời (kiểm chứng bằng công cụ kiểm thử tải như Artillery/k6).

* NFR-PERF-04: Trang chủ web hiển thị nội dung chính ≤ 3 giây trên mạng thông thường.

* NFR-PERF-05: Vị trí shipper cập nhật đến người mua với độ trễ ≤ 5 giây; tìm kiếm bằng hình ảnh trả kết quả ≤ 3 giây.

## **6.2. Bảo mật**

* NFR-SEC-01: Mật khẩu băm bằng BCrypt; không lưu mật khẩu dạng rõ.

* NFR-SEC-02: Access token JWT thời hạn ngắn (≤ 30 phút) kết hợp refresh token; thu hồi khi đăng xuất/khóa tài khoản.

* NFR-SEC-03: Phân quyền kiểm tra ở cả API Gateway và từng service; người dùng chỉ truy cập được dữ liệu thuộc quyền của mình (Seller chỉ thao tác gian hàng của mình, Buyer chỉ xem đơn của mình).

* NFR-SEC-04: Toàn bộ giao tiếp qua HTTPS; xác minh chữ ký webhook PayOS; endpoint webhook có cơ chế idempotent.

* NFR-SEC-05: Phòng chống các lỗ hổng phổ biến theo OWASP Top 10 (SQL Injection, XSS, CSRF, IDOR…); dữ liệu đầu vào được kiểm tra hợp lệ ở cả client và server.

* NFR-SEC-06: Nhật ký (log) không ghi thông tin nhạy cảm (mật khẩu, token, thông tin thanh toán).

* NFR-SEC-07: Vị trí GPS của shipper chỉ được thu thập trong thời gian thực hiện đơn giao và chỉ hiển thị cho người mua của đơn đó, người bán liên quan và Admin; ảnh tìm kiếm của người dùng không lưu quá 24 giờ.

## **6.3. Khả năng mở rộng**

* NFR-SCA-01: Các service stateless, đóng gói container, có thể nhân bản (scale ngang) độc lập từng service.

* NFR-SCA-02: Mô hình database-per-service cho phép tách và mở rộng kho dữ liệu theo từng dịch vụ.

* NFR-SCA-03: Bổ sung service mới (ví dụ khuyến mãi, vận chuyển) không yêu cầu sửa đổi lớn các service hiện có nhờ giao tiếp qua sự kiện.

## **6.4. Độ tin cậy và sẵn sàng**

* NFR-REL-01: Mục tiêu uptime ≥ 99% trong giai đoạn demo/bảo vệ.

* NFR-REL-02: Thông điệp sự kiện lỗi được retry và chuyển vào DLQ; một service AI gặp sự cố không làm gián đoạn luồng mua hàng cốt lõi (suy giảm có kiểm soát).

* NFR-REL-03: Sao lưu CSDL tự động hằng ngày; có kịch bản khôi phục.

## **6.5. Khả năng bảo trì**

* NFR-MAI-01: Mã nguồn tổ chức theo chuẩn mỗi service một repository/module; quy ước đặt tên, review code qua pull request.

* NFR-MAI-02: CI/CD tự động build, chạy unit test và triển khai bằng GitHub Actions; độ phủ unit test tối thiểu 60% cho tầng nghiệp vụ các service lõi.

* NFR-MAI-03: Log tập trung, health check endpoint cho từng service; tài liệu API cập nhật tự động (Swagger).

## **6.6. Tính khả dụng**

* NFR-USA-01: Giao diện tiếng Việt, nhất quán về bố cục và thuật ngữ trên cả ba sản phẩm.

* NFR-USA-02: Người mua mới hoàn tất luồng "tìm sản phẩm → đặt hàng" không quá 5 phút mà không cần hướng dẫn.

* NFR-USA-03: Hỗ trợ đầy đủ trên màn hình di động từ 360 px; thao tác chính không quá 3 lần chạm/nhấp từ trang chủ.

# **7\. Mô hình dữ liệu và phân quyền**

## **7.1. Chiến lược dữ liệu theo service**

Mỗi microservice sở hữu kho dữ liệu riêng, lựa chọn công nghệ phù hợp đặc thù dữ liệu; các service khác chỉ truy cập qua API hoặc sự kiện. Chi tiết lược đồ (ERD) được trình bày trong tài liệu thiết kế CSDL.

| Service | CSDL | Thực thể chính |
| ----- | ----- | ----- |
| Auth | PostgreSQL | User, Role, UserRole, RefreshToken |
| User | PostgreSQL | UserProfile, Address |
| Shop | PostgreSQL | Shop, ShopStatusHistory |
| Product Catalog | MongoDB | Product, ProductVariant, Category, Brand |
| Cart | Redis | Cart, CartItem (theo userId) |
| Order | PostgreSQL | Order, OrderItem, OrderStatusHistory, ReturnRequest, Complaint |
| Payment | PostgreSQL | PaymentTransaction, RefundRequest |
| Delivery | PostgreSQL \+ Redis | Shipper, DeliveryAssignment, DeliveryStatusHistory, RouteLog; LocationPing (Redis – vị trí mới nhất) |
| Review | PostgreSQL | Review, ReviewImage, SellerReply |
| Notification | MongoDB | Notification, EmailLog |
| AI Search | Elasticsearch | ProductIndex, ProductImageEmbedding (chỉ mục đồng bộ từ Product) |
| Recommendation | PostgreSQL \+ Redis | UserEvent, ItemFeature, RecommendationCache |
| AI Chatbot | MongoDB \+ Vector store | Conversation, Message, KnowledgeChunk |

## **7.2. Ma trận phân quyền theo vai trò (RBAC)**

Ký hiệu: ✓ – được phép; ô trống – không được phép. Quyền của vai trò cao hơn không bao hàm mặc nhiên quyền của vai trò khác (Admin không thao tác thay gian hàng của Seller trừ chức năng kiểm duyệt).

| Chức năng | Guest | Buyer | Seller | Shipper | Admin |
| ----- | :---: | :---: | :---: | :---: | :---: |
| Duyệt / tìm kiếm / xem sản phẩm | ✓ | ✓ | ✓ | ✓ | ✓ |
| Tìm kiếm bằng hình ảnh | ✓ | ✓ | ✓ | ✓ | ✓ |
| Hỏi đáp chatbot (thông tin chung) | ✓ | ✓ | ✓ | ✓ | ✓ |
| Giỏ hàng, đặt hàng, thanh toán |  | ✓ | ✓ |  |  |
| Theo dõi / hủy đơn của mình |  | ✓ | ✓ |  |  |
| Theo dõi vị trí giao đơn của mình |  | ✓ | ✓ |  | ✓ |
| Yêu cầu trả hàng cho đơn của mình |  | ✓ | ✓ |  |  |
| Đánh giá sản phẩm đã mua |  | ✓ | ✓ |  |  |
| Tra cứu đơn của mình qua chatbot |  | ✓ | ✓ |  |  |
| Đăng ký mở gian hàng |  | ✓ |  |  |  |
| Quản lý sản phẩm, tồn kho gian hàng |  |  | ✓ |  |  |
| Xử lý đơn hàng của gian hàng |  |  | ✓ |  |  |
| Duyệt yêu cầu trả hàng của gian hàng |  |  | ✓ |  |  |
| Phản hồi đánh giá; thống kê gian hàng |  |  | ✓ |  |  |
| Nhận đơn phân công, cập nhật trạng thái giao |  |  |  | ✓ |  |
| Chia sẻ vị trí GPS khi giao hàng |  |  |  | ✓ |  |
| Duyệt / đình chỉ gian hàng |  |  |  |  | ✓ |
| Kiểm duyệt sản phẩm, đánh giá |  |  |  |  | ✓ |
| Quản lý người dùng, danh mục |  |  |  |  | ✓ |
| Quản lý và phân công shipper |  |  |  |  | ✓ |
| Phân xử trả hàng, khiếu nại; hoàn tiền |  |  |  |  | ✓ |
| Thống kê toàn nền tảng |  |  |  |  | ✓ |

Ma trận trên là căn cứ thiết kế bảng Role/Permission trong CSDL và kiểm tra quyền tại API Gateway cùng từng service, đáp ứng yêu cầu "thiết kế CSDL có phân quyền chi tiết" của học phần.

# **8\. Phụ lục**

## **8.1. Phụ lục A – Các sự kiện chính trên Event Bus**

| Sự kiện | Service phát | Service nhận | Ý nghĩa |
| ----- | ----- | ----- | ----- |
| OrderPlaced | Order | Product, Payment, Notification, Recommendation | Đơn mới được tạo; tạm giữ tồn kho, khởi tạo thanh toán. |
| PaymentSucceeded / PaymentFailed | Payment | Order, Notification | Kết quả thanh toán PayOS; cập nhật trạng thái đơn. |
| OrderStatusChanged | Order | Notification, Recommendation | Đơn chuyển trạng thái; thông báo các bên. |
| StockReserved / StockReleased | Product | Order | Kết quả giữ/hoàn tồn kho cho đơn hàng. |
| ProductCreated / Updated / Deleted | Product | Search, Recommendation | Đồng bộ chỉ mục tìm kiếm và đặc trưng gợi ý. |
| ShopApproved / ShopSuspended | Shop | Auth, Product, Notification | Cấp vai trò Seller; ẩn/hiện sản phẩm gian hàng. |
| ReviewCreated | Review | Product, Notification | Cập nhật điểm trung bình; thông báo Seller. |
| UserBehaviorTracked | Gateway/Clients | Recommendation | Sự kiện xem, thêm giỏ, mua phục vụ huấn luyện gợi ý. |
| DeliveryAssigned | Delivery | Order, Notification | Đơn được phân công cho shipper; thông báo shipper và người bán. |
| DeliveryStatusChanged | Delivery | Order, Notification | Cập nhật lấy hàng / đang giao / giao thành công / thất bại; đồng bộ trạng thái đơn. |
| ShipperLocationUpdated | Delivery | Gateway (đẩy WebSocket tới client) | Tọa độ mới của shipper phục vụ theo dõi thời gian thực. |
| ReturnRequested / ReturnResolved | Order | Payment, Product, Notification | Yêu cầu trả hàng được tạo / chốt kết quả; kích hoạt hoàn tiền và cập nhật tồn kho. |

## **8.2. Phụ lục B – Tiêu chí chấp nhận tổng quát**

* Ba sản phẩm (Web Admin, Web end-user, Mobile) được triển khai thực tế trên môi trường công khai và truy cập được trong buổi bảo vệ.

* Luồng nghiệp vụ đầu-cuối chạy thông suốt: đăng ký gian hàng → duyệt → đăng sản phẩm → tìm kiếm → đặt hàng → thanh toán PayOS (môi trường thử nghiệm) → phân công shipper giao hàng và người mua theo dõi vị trí → nhận hàng → đánh giá; kèm luồng trả hàng – hoàn tiền được nghiệm thu riêng.

* Bốn tính năng AI (tìm kiếm từ khóa thông minh, tìm kiếm bằng hình ảnh CNN, gợi ý sản phẩm, chatbot) hoạt động được với dữ liệu thật của hệ thống.

* Toàn bộ yêu cầu mức ưu tiên "Cao" trong tài liệu này được hoàn thành và vượt qua kiểm thử; các yêu cầu "TB"/"Thấp" được đánh giá theo mức độ hoàn thiện.

* Hồ sơ bàn giao kèm theo: Requirement Outline, SRS (tài liệu này), tài liệu thiết kế, tài liệu kiểm thử, tài liệu triển khai, mã nguồn và slide bảo vệ.

— Hết tài liệu —

