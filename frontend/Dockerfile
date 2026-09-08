# Dockerfile FE (PBL6-3) - dong goi SPA React/Vite thanh image tinh chay bang nginx.
#
# Build tu thu muc frontend:  docker build -t vmarket-frontend .
# (docker-compose.yml da cau hinh build context ./frontend cho service frontend)
#
# LUU Y VE CAU TRUC: khac backend (moi microservice mot Dockerfile rieng), FE la
# MOT codebase dung chung cho Web end-user / Web Admin / Mobile nen chi can DUY
# NHAT file nay - day la chu y thiet ke, khong phai thieu sot.

# ---- Build stage: build SPA bang Vite ----
FROM node:22-alpine AS build
WORKDIR /app

# Bo qua hooks husky khi "npm ci" chay script "prepare" (trong container khong co .git)
ENV HUSKY=0

# Chi copy lockfile truoc de tang layer dependencies, build nhanh hon khi sua code
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .

# Vite doc bien VITE_* luc build nen gia tri duoc "bake" cung vao bundle.
# MAC DINH DE RONG: browser goi duong dan tuong doi (/api/...) ve chinh origin
# cua FE, nginx reverse proxy tiep sang Gateway => cung origin, khong dinh CORS.
# Chi dat gia tri khi CO CHU DINH cho browser goi thang Gateway (vd FE deploy
# tach roi, khong di qua nginx nay) - luc do BE phai bat CORS cho origin FE.
ARG VITE_API_BASE_URL=
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# ---- Runtime stage: phuc vu file tinh + reverse proxy bang nginx ----
FROM nginx:1.28-alpine

# nginx.conf: phan http tinh. default.conf.template: server block co bien moi
# truong, entrypoint cua image chay envsubst -> /etc/nginx/conf.d/default.conf.
COPY nginx.conf /etc/nginx/nginx.conf
COPY default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

# Dich den cua reverse proxy /api/* - doi duoc luc RUNTIME (khong build lai image).
ENV API_GATEWAY_URL=http://api-gateway:8080
# DNS noi bo cua Docker cho user-defined network (dung de resolve ten service).
ENV NGINX_RESOLVER=127.0.0.11

# Chay non-root giong cac service Java (user nginx co san trong image).
# conf.d phai ghi duoc vi entrypoint (chay bang user nginx) ghi ket qua envsubst
# vao day; xoa default.conf cua image de khong con server :80 thua.
RUN rm -f /etc/nginx/conf.d/default.conf \
 && chown -R nginx:nginx /usr/share/nginx/html /etc/nginx/conf.d
USER nginx

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO /dev/null http://127.0.0.1:8080/healthz || exit 1
