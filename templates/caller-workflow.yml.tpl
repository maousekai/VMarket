# =============================================================================
# KHUÔN MẪU workflow cho MỘT service Spring Boot mới
# =============================================================================
# Cách dùng:
#   1. Copy file này thành .github/workflows/<SERVICE_NAME>.yml
#   2. Thay toàn bộ __SERVICE_NAME__ / __SERVICE_PORT__ / __DB_NAME__
#   3. Commit — pipeline tự chạy khi có thay đổi trong thư mục service đó
#
# Hoặc chạy script tự động:  scripts/new-service.ps1 <ten-service> <port>
#
# Bảng port & database chuẩn của VMarket (theo README + SRS §7.1):
#   api-gateway 8080 | auth 8081 vmarket_auth | user 8082 vmarket_user
#   shop 8083 vmarket_shop | product 8084 (MongoDB) | cart 8085 (Redis)
#   order 8086 vmarket_order | payment 8087 vmarket_payment
#   delivery 8088 vmarket_delivery | review 8089 vmarket_review
#   notification 8090 (MongoDB)
# =============================================================================

name: __SERVICE_NAME__

on:
  push:
    branches: [dev, release, product]
    paths:
      - 'services/__SERVICE_NAME__/**'
      - 'services/pom.xml'
      - 'templates/Dockerfile.springboot'
      - '.github/workflows/__SERVICE_NAME__.yml'
      - '.github/workflows/_reusable-springboot-service.yml'
  pull_request:
    paths:
      - 'services/__SERVICE_NAME__/**'
      - 'services/pom.xml'
      - 'templates/Dockerfile.springboot'
      - '.github/workflows/__SERVICE_NAME__.yml'
      - '.github/workflows/_reusable-springboot-service.yml'
  workflow_dispatch:

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: read
  packages: write

jobs:
  ci-cd:
    uses: ./.github/workflows/_reusable-springboot-service.yml
    with:
      service-name: __SERVICE_NAME__
      service-port: __SERVICE_PORT__
      db-name: __DB_NAME__
      java-version: '17'
      coverage-min: 0          # nâng lên 60 khi đã có unit test nghiệp vụ
      smoke-test: true
      push-image: ${{ github.event_name == 'push' }}
      deploy: false
    secrets: inherit
