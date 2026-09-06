#!/usr/bin/env bash
# =============================================================================
# VMarket — tạo bộ khung CI/CD cho một microservice Spring Boot mới
# (bản Linux/macOS; trên Windows dùng scripts/new-service.ps1)
# =============================================================================
# Dùng:  ./scripts/new-service.sh <ten-service> <port> [ten-database]
# Ví dụ: ./scripts/new-service.sh order-service 8086
#
# Sinh sẵn từ templates/:
#   services/<ten>/env/{.env.example,.env.dev,.env.prod.example}
#   .github/workflows/<ten>.yml   (kèm path-based trigger)
# KHÔNG ghi đè file đã tồn tại. KHÔNG sinh code Java (xem hướng dẫn in ở cuối).
# =============================================================================
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Dùng: $0 <ten-service> <port> [ten-database]" >&2
  echo "Ví dụ: $0 order-service 8086" >&2
  exit 1
fi

NAME="$1"
PORT="$2"

if ! [[ "$NAME" =~ ^[a-z][a-z0-9-]*-service$ ]]; then
  echo "Lỗi: tên service phải viết thường và kết thúc bằng '-service' (vd: order-service)" >&2
  exit 1
fi
if ! [[ "$PORT" =~ ^[0-9]+$ ]] || [ "$PORT" -lt 8080 ] || [ "$PORT" -gt 8199 ]; then
  echo "Lỗi: port phải là số trong khoảng 8080-8199" >&2
  exit 1
fi

# order-service -> vmarket_order
DEFAULT_DB="vmarket_$(echo "${NAME%-service}" | tr '-' '_')"
DB_NAME="${3:-$DEFAULT_DB}"

# Phải validate như $NAME và $PORT: $DB_NAME được nhét thẳng vào phần thay thế
# của `sed` bên dưới, nên ký tự `/` hoặc `&` sẽ phá cú pháp lệnh sed (hoặc chèn
# nội dung ngoài ý muốn vào file sinh ra). Đây cũng là tên database thật nên
# giới hạn theo đúng quy tắc định danh của PostgreSQL.
if ! [[ "$DB_NAME" =~ ^[a-z][a-z0-9_]*$ ]]; then
  echo "Lỗi: tên database phải viết thường, bắt đầu bằng chữ cái, chỉ gồm [a-z0-9_] (vd: vmarket_order)" >&2
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TPL_DIR="$REPO_ROOT/templates"
ENV_DIR="$REPO_ROOT/services/$NAME/env"
WORKFLOW_OUT="$REPO_ROOT/.github/workflows/$NAME.yml"

echo ""
echo "=== Tạo khung CI/CD cho service mới ==="
echo "  Service : $NAME"
echo "  Port    : $PORT"
echo "  Database: $DB_NAME"
echo ""

copy_template() {
  local from="$1" to="$2"
  if [ -e "$to" ]; then
    echo "  [BỎ QUA] đã tồn tại: ${to#$REPO_ROOT/}"
    return
  fi
  [ -f "$from" ] || { echo "Không tìm thấy template: $from" >&2; exit 1; }
  sed -e "s/__SERVICE_NAME__/$NAME/g" \
      -e "s/__SERVICE_PORT__/$PORT/g" \
      -e "s/__DB_NAME__/$DB_NAME/g" \
      "$from" > "$to"
  echo "  [TẠO] ${to#$REPO_ROOT/}"
}

mkdir -p "$ENV_DIR" "$REPO_ROOT/.github/workflows"

copy_template "$TPL_DIR/env/.env.example.tpl"      "$ENV_DIR/.env.example"
copy_template "$TPL_DIR/env/.env.dev.tpl"          "$ENV_DIR/.env.dev"
copy_template "$TPL_DIR/env/.env.prod.example.tpl" "$ENV_DIR/.env.prod.example"
copy_template "$TPL_DIR/caller-workflow.yml.tpl"   "$WORKFLOW_OUT"

PKG="${NAME%-service}"
cat <<EOF

=== CÒN 4 BƯỚC LÀM TAY ===

1) Sinh code Spring Boot vào services/$NAME (nếu chưa có):

   curl -o $NAME.zip "https://start.spring.io/starter.zip?type=maven-project&language=java&javaVersion=17&groupId=com.vmarket&artifactId=$NAME&name=$NAME&packageName=com.vmarket.$PKG&dependencies=web,actuator,validation,lombok"

   Sau khi giải nén: sửa services/$NAME/pom.xml để <parent> trỏ về
   com.vmarket:vmarket-services (relativePath ../pom.xml) — copy y hệt
   services/auth-service/pom.xml.

2) Khai báo module trong services/pom.xml:

       <module>$NAME</module>

3) Thêm block vào docker-compose.yml (mẫu comment "MAU BLOCK BUSINESS SERVICE")
   và docker-compose.prod.yml.

4) Kiểm tra tại chỗ trước khi push:

       cd services && ./mvnw -pl $NAME -am verify
       docker build -f templates/Dockerfile.springboot \\
         --build-arg SERVICE_NAME=$NAME --build-arg SERVICE_PORT=$PORT \\
         -t vmarket-$NAME:local .

Chi tiết đầy đủ: docs/CICD-TEMPLATE.md
EOF
