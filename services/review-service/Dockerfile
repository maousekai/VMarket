# syntax=docker/dockerfile:1.7
# =============================================================================
# VMarket - Dockerfile cho review-service (port 8089)
# =============================================================================
# File nay sinh ra tu template dung chung: docs/templates/Dockerfile.springboot
# Than file GIONG HET moi service, chi khac 2 dong ARG SERVICE_NAME/SERVICE_PORT.
# => Sua logic build thi sua TEMPLATE truoc roi dong bo lai cho cac service.
# BUILD (context BAT BUOC la thu muc goc repo vi can services/pom.xml):
#   docker build -f services/<ten-service>/Dockerfile -t vmarket-<ten-service> .
#
# TAI SAO 3 STAGE:
#   deps    - chi COPY pom roi tai dependency. Layer nay CHI doi khi pom doi,
#             nen sua code Java se dung lai cache -> build lai nhanh (vai giay
#             thay vi tai lai ca .m2). Day la khac biet lon nhat so voi kieu
#             COPY het roi mvn package.
#   build   - COPY src va dong goi jar.
#   runtime - chi chua JRE slim + file jar (khong co Maven, khong co source
#             code, khong co .m2) -> image nho va it be mat tan cong hon.
# =============================================================================

# --- THAM SO RIENG CUA SERVICE: copy template xong chi can sua 2 dong nay ---
ARG SERVICE_NAME=review-service
ARG SERVICE_PORT=8089

# --- Base image dung chung (nang version tap trung o day) ---
# JAVA_VERSION la MOT CHO DUY NHAT khai bao JDK cho ca build lan runtime.
# CI truyen lai gia tri nay qua build-args, lay tu input java-version cua
# .github/workflows/service-ci.yml -> doi JDK o file goi CI thi image di theo,
# khong con canh "test chay JDK 21 ma image van JDK 17".
ARG JAVA_VERSION=17
# maven:3.9-* khop voi Maven Wrapper cua repo (services/.mvn -> 3.9.x)
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-${JAVA_VERSION}
# JRE slim: chi co moi truong chay, khong kem JDK/compiler
ARG RUNTIME_IMAGE=eclipse-temurin:${JAVA_VERSION}-jre-alpine
# Profile mac dinh bake vao image. Runtime van ghi de duoc bang bien
# SPRING_PROFILES_ACTIVE (xem .env.example / .env.prod.example cua service).
ARG SPRING_PROFILE=dev


# ---------- Stage 1: deps - tai dependency (layer cache theo pom) ----------
FROM ${MAVEN_IMAGE} AS deps
ARG SERVICE_NAME
WORKDIR /build

# Parent POM (quan ly version Boot/Cloud/Lombok) + POM cua rieng service.
# Chua COPY src o day -> doi code KHONG lam mat cache cua layer tai dependency.
COPY services/pom.xml ./pom.xml
COPY services/${SERVICE_NAME}/pom.xml ${SERVICE_NAME}/pom.xml

# go-offline keo ve toan bo dependency + plugin can cho build.
# Dependency nam han trong layer image (khong dung cache mount) de CI
# tren runner moi tinh van tai lai duoc tu Docker layer cache.
RUN mvn -B --no-transfer-progress -f ${SERVICE_NAME}/pom.xml \
        dependency:go-offline -DskipTests


# ---------- Stage 2: build - dong goi jar ----------
FROM deps AS build
ARG SERVICE_NAME

COPY services/${SERVICE_NAME}/src ${SERVICE_NAME}/src

# -DskipTests: test da chay o job rieng trong CI (xem .github/workflows/
# service-ci.yml) - nhanh hon va co bao cao loi ro rang hon trong docker build.
# Van compile test de bat loi cu phap (khac -Dmaven.test.skip=true).
# Doi ten jar ve mot duong dan co dinh de stage runtime khong can biet
# ten/version artifact.
RUN mvn -B --no-transfer-progress -f ${SERVICE_NAME}/pom.xml \
        clean package -DskipTests \
 && cp ${SERVICE_NAME}/target/*.jar /build/app.jar


# ---------- Stage 3: runtime - JRE slim ----------
FROM ${RUNTIME_IMAGE} AS runtime
ARG SERVICE_NAME
ARG SERVICE_PORT
ARG SPRING_PROFILE
WORKDIR /app

# Chay bang user thuong, khong phai root (yeu cau bao mat co ban).
RUN addgroup -S vmarket && adduser -S vmarket -G vmarket

COPY --from=build --chown=vmarket:vmarket /build/app.jar /app/app.jar
USER vmarket

# Cau hinh mac dinh - deploy that ghi de bang bien moi truong / --env-file.
#   SERVER_PORT           : application.yml doc bien nay (server.port)
#   SPRING_PROFILES_ACTIVE: dev | prod (tach cau hinh theo application-<profile>.yml)
#   JAVA_OPTS             : tuy chinh JVM (MaxRAMPercentage de JVM ton trong
#                           gioi han RAM cua container thay vi RAM cua host)
#   HEALTHCHECK_PATH      : xem phan HEALTHCHECK ben duoi
ENV SERVER_PORT=${SERVICE_PORT} \
    SPRING_PROFILES_ACTIVE=${SPRING_PROFILE} \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0" \
    HEALTHCHECK_PATH="/actuator/health/liveness"

EXPOSE ${SERVICE_PORT}

# Dung wget cua busybox (co san trong alpine) - khong can cai them goi.
#
# TAI SAO /actuator/health/liveness CHU KHONG PHAI /actuator/health:
# endpoint tong hop /actuator/health gop ca suc khoe cua PHU THUOC BEN NGOAI
# (PostgreSQL, MongoDB, RabbitMQ). Do do RabbitMQ chop tat mot lat la container
# bi danh dau unhealthy du ung dung van phuc vu binh thuong - te hon nua, voi
# service dung Mongo thi endpoint nay CHO DEN HET TIMEOUT (do kiem thuc te).
# 'liveness' chi tra loi "tien trinh con song", dung nghia cua HEALTHCHECK.
# Can kiem ca phu thuoc thi doi bien: -e HEALTHCHECK_PATH=/actuator/health
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD wget -qO- "http://localhost:${SERVER_PORT}${HEALTHCHECK_PATH}" >/dev/null 2>&1 || exit 1

# Nhan truy vet: biet image nay thuoc service nao, build tu commit nao.
LABEL org.opencontainers.image.title="vmarket-${SERVICE_NAME}" \
      org.opencontainers.image.source="https://github.com/maousekai/VMarket" \
      org.opencontainers.image.vendor="VMarket (PBL6)"

# sh -c + exec: can shell de bung bien $JAVA_OPTS, `exec` de java thanh PID 1
# nen van nhan duoc SIGTERM tu `docker stop` (shutdown muot, khong bi kill 9).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
