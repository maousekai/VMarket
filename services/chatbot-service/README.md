# AI Chatbot Service (FR-BOT-01–04)

Trợ lý hỗ trợ khách hàng của VMarket: hỏi đáp RAG trên kho tri thức (FAQ/chính sách + catalog),
tra cứu đơn hàng của chính người hỏi, chuyển tiếp hỗ trợ và lịch sử hội thoại.

- FastAPI, cổng **8102**, route qua gateway: `/api/ai/chat/**`
- MongoDB `vmarket_chatbot`: `conversations`, `messages`, `knowledge_chunks` (kèm vector), `support_tickets`
- Hợp đồng API: [docs/openapi/chatbot-service.yaml](../../docs/openapi/chatbot-service.yaml)

## Chạy local

```bash
docker compose up -d mongo                 # từ gốc repo (Mongo publish ra host ở 27018)
cd services/chatbot-service
python -m venv .venv && .venv\Scripts\activate    # macOS/Linux: source .venv/bin/activate
pip install -r requirements-dev.txt
copy .env.example .env                     # macOS/Linux: cp
uvicorn main:app --port 8102
```

Lần khởi động đầu, nếu kho tri thức trống, service tự nạp `knowledge/*.md`. Thử nhanh:

```bash
curl -X POST http://localhost:8102/api/ai/chat/messages -H "Content-Type: application/json" \
  -d "{\"message\": \"Chính sách đổi trả như thế nào?\"}"
```

Test: `python -m unittest discover -s tests/unit -v` (không cần hạ tầng) và
`python -m unittest discover -s tests/integration -v` (cần MongoDB; đặt `MONGO_HOST`/`MONGO_PORT` nếu khác mặc định).

Docker (context là chính thư mục service): `docker build -t vmarket-chatbot services/chatbot-service`.

## Một lượt chat được xử lý thế nào

Ý định được quyết định bằng luật cố định, theo thứ tự:

1. **Yêu cầu gặp người hỗ trợ** (FR-BOT-03) → người đã đăng nhập được tạo phiếu hỗ trợ; khách được hướng dẫn liên hệ.
2. **Hỏi trạng thái đơn hàng** (FR-BOT-02) → gọi order-service; khách được yêu cầu đăng nhập.
3. **Chào hỏi / cảm ơn** → câu chào cố định.
4. **Còn lại là RAG** (FR-BOT-01): tìm các đoạn tri thức gần nhất theo cosine.
   - Không đoạn nào đạt `RETRIEVAL_MIN_SCORE` → **từ chối lịch sự**, không gọi LLM.
   - Có LLM → LLM chỉ được trả lời dựa trên các đoạn đó; nếu nó thấy ngữ cảnh không đủ thì trả `NO_ANSWER` và người dùng nhận câu từ chối.
   - Không cấu hình LLM → chế độ **trích dẫn**: trả nguyên văn đoạn khớp nhất.

Mọi câu trả lời RAG kèm `sources` (đoạn tri thức đã dùng). LLM lỗi hoặc quá hạn → `503 CHAT_UNAVAILABLE` ("thử lại sau").

## Danh tính và dữ liệu riêng tư

- Danh tính **chỉ** lấy từ access token đã verify (HS256, `AUTH_JWT_SECRET`, issuer `auth-service`) — không đọc
  `userId` từ body (field lạ bị từ chối 400) và không tin header `X-User-*`.
- `POST /messages` và `/health` là public ở gateway để khách hỏi đáp chung, nên gateway không verify token ở
  route này; service tự verify. Token sai/hết hạn → 401, **không** hạ xuống thành khách.
- Tra cứu đơn: service chuyển tiếp nguyên token của người hỏi tới `GET /api/orders[/{id}]`. Order-service tự
  suy ra chủ đơn từ token, nên mã đơn của người khác trả 404 y như mã không tồn tại. Chatbot không có cách nào
  chỉ định người dùng khác.
- Câu trả lời đơn hàng chỉ gồm mã, trạng thái, thời điểm đặt, số sản phẩm, tổng tiền — không lặp lại địa chỉ/số
  điện thoại. Dữ liệu đơn hàng **không bao giờ** được gửi tới nhà cung cấp LLM, kể cả qua lịch sử hội thoại.
- Lịch sử và phiếu hỗ trợ luôn được lọc theo `userId` của chủ sở hữu ngay ở tầng truy vấn MongoDB; id hội thoại
  của người khác trả 404. Vai trò `ADMIN` xem được mọi phiếu hỗ trợ.
- Log không ghi nội dung chat, token hay URL.

## Kho tri thức

```bash
python manage.py ingest             # FAQ/chính sách trong knowledge/*.md
python manage.py ingest --catalog   # thêm sản phẩm đang hiển thị từ product-service
```

- Mỗi mục `##` trong `knowledge/*.md` là một đoạn tri thức; tiêu đề mục được tính trọng số cao khi tìm, nên hãy
  viết tiêu đề bằng đúng từ người mua hay dùng. Nội dung hiện tại biên soạn từ `docs/SRS-VMarket.md`.
- `--catalog` đọc API export nội bộ của product-service (`X-Internal-Api-Key`, cần `INTERNAL_API_KEY`), mỗi sản
  phẩm đang hiển thị thành một đoạn (tên, giá, tình trạng hàng, đánh giá, mô tả).
- Nạp lại là idempotent: chỉ đoạn đổi nội dung mới được embed lại, đoạn không còn ở nguồn bị xóa. Service đang
  chạy tự đọc lại kho sau tối đa `KNOWLEDGE_REFRESH_SECONDS`.

### Embedding

| `EMBEDDING_PROVIDER` | Cách hoạt động | Khi nào dùng |
| --- | --- | --- |
| `hashing` (mặc định) | Vector 1024 chiều băm từ từ đơn và cặp từ liền kề, không dấu. Chạy offline, không cần model. | Dev, demo, CI |
| `openai_compatible` | Gọi `LLM_BASE_URL/embeddings` với `EMBEDDING_MODEL` (OpenAI-compatible, kể cả Ollama). | Khi cần hiểu ngữ nghĩa |

`hashing` là so khớp **từ vựng**, không phải ngữ nghĩa: câu hỏi diễn đạt bằng từ khác hẳn kho tri thức sẽ bị từ
chối thay vì được trả lời. Đổi provider/model phải chạy lại `manage.py ingest` (vector cũ bị bỏ qua) và hiệu
chỉnh lại `RETRIEVAL_MIN_SCORE` — ngưỡng 0.25 chỉ đúng cho `hashing`. Bộ câu hỏi trong/ngoài phạm vi dùng để
hiệu chỉnh nằm ở `tests/unit/test_chatbot.py`.

### LLM

Bất kỳ endpoint OpenAI-compatible `/chat/completions`. Đặt `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY` và
`ALLOW_EXTERNAL_QUERY_TEXT=true` (xác nhận cho phép gửi câu hỏi + đoạn tri thức ra nhà cung cấp ngoài). Với
`LLM_PROVIDER=ollama` thì không cần key và được dùng `http://`.

## Giới hạn hiện tại

- Catalog không đồng bộ theo sự kiện: giá/tồn kho trong câu trả lời là của lần chạy `ingest --catalog` gần nhất.
- Tìm vector là quét toàn bộ trong bộ nhớ — phù hợp FAQ + vài nghìn sản phẩm; lớn hơn cần chỉ mục ANN.
- Nhận diện ý định bằng luật từ khóa tiếng Việt; câu hỏi đơn hàng diễn đạt lạ có thể rơi vào RAG.
- Trạng thái đơn hiển thị theo enum hiện có của order-service (`PENDING`…`CANCELLED`).
- Phiếu hỗ trợ mới chỉ được tạo và liệt kê (`OPEN`); chưa có luồng xử lý/đóng phiếu và chưa gửi thông báo.
- Chưa có giao diện chat ở frontend, chưa có block trong `docker-compose.yml`; giới hạn tần suất dùng rate limit
  theo IP của gateway.
