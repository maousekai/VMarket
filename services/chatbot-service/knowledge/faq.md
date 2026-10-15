# Chính sách và hướng dẫn mua hàng VMarket

Nội dung biên soạn từ đặc tả yêu cầu VMarket (docs/SRS-VMarket.md). Mỗi mục `##` là một
đoạn tri thức độc lập; sửa file rồi chạy `python manage.py ingest` để cập nhật chatbot.

## Chính sách đổi trả hàng, trả hàng trong bao lâu

Bạn được yêu cầu trả hàng trong vòng 7 ngày kể từ khi đơn ở trạng thái "Đã giao". Lý do được chấp nhận: sản phẩm lỗi, không đúng mô tả, giao thiếu hoặc giao sai. Mỗi sản phẩm trong đơn chỉ được yêu cầu trả hàng một lần. Quá 7 ngày kể từ khi "Đã giao", hệ thống không cho tạo yêu cầu trả hàng.

## Cách tạo yêu cầu trả hàng

Mở chi tiết đơn hàng, chọn "Yêu cầu trả hàng", chọn sản phẩm cần trả, chọn lý do (lỗi, không đúng mô tả, giao thiếu hoặc sai) và tải lên tối đa 5 ảnh hoặc video minh chứng. Yêu cầu được tạo ở trạng thái "Chờ người bán xử lý" và người bán được thông báo.

## Người bán (seller) xử lý yêu cầu trả hàng trong bao lâu, bị từ chối trả hàng thì làm gì

Người bán chấp nhận hoặc từ chối (kèm lý do) yêu cầu trả hàng trong 48 giờ. Nếu người bán không phản hồi sau 48 giờ, yêu cầu tự động chuyển cho quản trị viên xử lý. Nếu người bán từ chối, bạn được khiếu nại lên quản trị viên; quản trị viên xem minh chứng của hai bên và ra quyết định cuối cùng.

## Hoàn tiền khi trả hàng hoặc hủy đơn đã thanh toán, bao giờ được hoàn tiền

Khi yêu cầu trả hàng được chấp nhận, hàng được thu hồi về gian hàng và người bán xác nhận đã nhận lại hàng, sau đó hệ thống ghi nhận hoàn tiền. Đơn đã thanh toán qua PayOS mà bị hủy cũng được ghi nhận hoàn tiền. Quản trị viên xác nhận hoàn tiền bằng chuyển khoản thủ công; trạng thái hoàn tiền đi từ "Chờ hoàn" sang "Đã hoàn". Với đơn COD, tiền mặt được hoàn theo xác nhận của hai bên hoặc quản trị viên.

## Phương thức thanh toán PayOS và COD

VMarket hỗ trợ hai phương thức thanh toán: thanh toán trực tuyến qua PayOS (quét mã QR VietQR hoặc mở liên kết thanh toán) và COD – thanh toán bằng tiền mặt khi nhận hàng. Đơn COD chuyển thẳng sang trạng thái "Chờ xác nhận".

## COD là gì, thanh toán khi nhận hàng

COD (Cash On Delivery) là thanh toán bằng tiền mặt khi nhận hàng. Bạn chọn COD ở bước đặt hàng; đơn chuyển thẳng sang trạng thái "Chờ xác nhận" và shipper thu tiền khi giao hàng thành công.

## Thời hạn thanh toán PayOS, hết hạn thanh toán thì sao

Với đơn chọn PayOS, bạn có 15 phút để thanh toán bằng mã QR hoặc liên kết thanh toán. Trong thời hạn này bạn có thể thanh toán lại nếu chưa thành công. Hết hạn mà chưa thanh toán, hệ thống tự động hủy đơn, hoàn lại tồn kho và thông báo cho bạn; bạn có thể đặt lại đơn mới.

## Cách đặt hàng

Thêm sản phẩm (theo biến thể như màu sắc, kích cỡ) vào giỏ hàng, vào trang thanh toán, chọn địa chỉ giao hàng và phương thức thanh toán (PayOS hoặc COD) rồi xác nhận đặt hàng. Khi vào trang thanh toán hệ thống kiểm tra lại giá và tồn kho mới nhất, cảnh báo nếu sản phẩm hết hàng hoặc đổi giá. Giỏ có sản phẩm của nhiều gian hàng sẽ được tách thành các đơn riêng theo từng gian hàng.

## Hủy đơn hàng

Bạn được hủy đơn khi đơn chưa được người bán xác nhận. Khi đơn đã được xác nhận và đang chuẩn bị hàng, bạn cần liên hệ người bán. Hủy đơn thì tồn kho được hoàn lại; đơn đã thanh toán được ghi nhận hoàn tiền.

## Các trạng thái của đơn hàng

Đơn hàng đi qua các trạng thái: Chờ thanh toán, Chờ xác nhận, Đang chuẩn bị, Chờ giao (đã phân công shipper), Đang giao, Đã giao, Hoàn thành. Ngoài ra có các nhánh Đã hủy và Trả hàng/Hoàn tiền. Đơn "Đã giao" tự chuyển "Hoàn thành" sau 7 ngày nếu không có yêu cầu trả hàng.

## Theo dõi đơn hàng và vị trí shipper giao hàng

Bạn xem lịch sử và chi tiết đơn trong mục đơn hàng của mình. Khi đơn đang giao, bạn xem được vị trí shipper theo thời gian thực trên bản đồ cùng trạng thái đơn. Bạn cũng có thể hỏi chatbot "đơn hàng của tôi tới đâu rồi" sau khi đăng nhập để tra cứu trạng thái đơn.

## Giao hàng thất bại, giao lại

VMarket giao hàng bằng đội shipper nội bộ. Nếu giao thất bại, đơn được giao lại tối đa 2 lần. Quá số lần giao lại, đơn chuyển sang quy trình hoàn về gian hàng và được xử lý hoàn tiền nếu đã thanh toán.

## Đánh giá sản phẩm

Bạn đánh giá sản phẩm từ 1 đến 5 sao, kèm bình luận và tối đa 5 ảnh, cho sản phẩm thuộc đơn đã "Hoàn thành". Mỗi sản phẩm trong đơn chỉ đánh giá một lần. Người bán có thể trả lời công khai đánh giá trong gian hàng của mình.

## Đăng ký tài khoản và xác thực email OTP

Bạn đăng ký tài khoản bằng email và mật khẩu. Hệ thống kiểm tra email trùng và độ mạnh mật khẩu, sau đó gửi email xác thực tài khoản (mã OTP). Khách chưa đăng nhập vẫn duyệt danh mục, tìm kiếm, xem sản phẩm và hỏi chatbot thông tin chung; muốn mua hàng cần có tài khoản.

## Quên mật khẩu, đổi mật khẩu, tài khoản bị khóa đăng nhập

Nếu quên mật khẩu, chọn "Quên mật khẩu" để nhận email chứa mã đặt lại mật khẩu có thời hạn. Khi đã đăng nhập, bạn đổi mật khẩu trong hồ sơ cá nhân và cần nhập mật khẩu hiện tại. Tài khoản bị khóa tạm thời sau 5 lần đăng nhập sai liên tiếp.

## Sổ địa chỉ giao hàng và hồ sơ cá nhân

Trong hồ sơ cá nhân bạn cập nhật họ tên, ảnh đại diện, số điện thoại, ngày sinh, giới tính. Sổ địa chỉ cho phép thêm, sửa, xóa địa chỉ giao hàng và đặt một địa chỉ mặc định.

## Đăng ký mở gian hàng (mở shop bán hàng), trở thành người bán

Người mua có thể đăng ký mở gian hàng với tên, mô tả, logo và thông tin liên hệ. Hồ sơ ở trạng thái "Chờ duyệt" cho đến khi quản trị viên phê duyệt hoặc từ chối (kèm lý do). Sau khi được duyệt, người bán quản lý sản phẩm, tồn kho và đơn hàng của gian hàng mình.

## Tìm kiếm sản phẩm bằng từ khóa và hình ảnh

Bạn tìm sản phẩm theo tên hoặc mô tả, gõ tiếng Việt có dấu hoặc không dấu đều được và hệ thống chịu được lỗi chính tả. Bạn cũng có thể tải lên hoặc chụp một ảnh (tối đa 10 MB) để tìm sản phẩm tương tự. Kết quả lọc được theo khoảng giá, danh mục và sắp xếp theo mới nhất, bán chạy, giá.

## Liên hệ hỗ trợ, khiếu nại

Với thắc mắc về một sản phẩm hoặc đơn hàng cụ thể, bạn liên hệ người bán qua trang gian hàng. Khi có tranh chấp giữa người mua và người bán, quản trị viên VMarket tiếp nhận và xử lý khiếu nại. Bạn cũng có thể nhắn chatbot "gặp nhân viên hỗ trợ" để tạo phiếu hỗ trợ gửi quản trị viên.
