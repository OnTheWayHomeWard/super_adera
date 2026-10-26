# Stage 1: build the static dashboard.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json .npmrc ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json vite.config.ts index.html ./
COPY public ./public
COPY src ./src
RUN npm run build

# Stage 2: nginx serves it under /backoffice/ (the gateway routes /backoffice/* here).
FROM nginxinc/nginx-unprivileged:1.27-alpine
USER root
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint.d/40-config.sh /docker-entrypoint.d/40-config.sh
RUN chmod +x /docker-entrypoint.d/40-config.sh
COPY --from=build /app/dist /usr/share/nginx/html/backoffice
RUN chown -R nginx:nginx /usr/share/nginx/html/backoffice
USER nginx
EXPOSE 8080
