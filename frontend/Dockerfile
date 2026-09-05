# Build tu thu muc frontend: docker build -t vmarket-frontend .
# (docker-compose.yml da cau hinh build context ./frontend cho service frontend)

# ---- Build stage: build SPA bang Vite ----
FROM node:22-alpine AS build
WORKDIR /app

# Bo qua hooks husky khi "npm ci" chay script "prepare" (trong container khong co .git)
ENV HUSKY=0

# Chi copy lockfile truoc de tang layer dependencies, build nhanh hon khi sua code
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .

# Vite doc bien VITE_* luc build nen API URL duoc "bake" vao bundle.
# Trinh duyet chay tren may host nen goi truc tiep Gateway qua localhost:8080.
ARG VITE_API_BASE_URL=http://localhost:8080
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# ---- Runtime stage: phuc vu file tinh bang nginx ----
FROM nginx:1.28-alpine

COPY nginx.conf /etc/nginx/nginx.conf
COPY --from=build /app/dist /usr/share/nginx/html

# Chay non-root giong cac service Java (user nginx co san trong image)
RUN chown -R nginx:nginx /usr/share/nginx/html
USER nginx

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO /dev/null http://127.0.0.1:8080/ || exit 1
