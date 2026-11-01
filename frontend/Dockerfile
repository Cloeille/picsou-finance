# ─── Stage 1: Build ────────────────────────────────────────────────────────
FROM node:26-alpine AS build

# Allow overriding demo mode at build time (default: false for production)
ARG VITE_DEMO_MODE=false
ENV VITE_DEMO_MODE=$VITE_DEMO_MODE

WORKDIR /app

COPY package*.json ./
RUN npm install --quiet

COPY . .
RUN npm run build

# ─── Stage 2: Serve with Nginx (non-root) ──────────────────────────────────
# nginx-unprivileged runs entirely as the nginx user (no root), listening on 8080
FROM nginxinc/nginx-unprivileged:1.27-alpine

COPY --from=build /app/dist /usr/share/nginx/html
COPY picsou-realip.conf /etc/nginx/conf.d/00-picsou-realip.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080

CMD ["nginx", "-g", "daemon off;"]
