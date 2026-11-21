# Bridge Lab: one container, Node 22, no npm dependencies.
FROM node:22-alpine
WORKDIR /app
COPY server ./server
COPY public ./public
ENV NODE_ENV=production PORT=8095 ALT_PORT=8096
USER node
EXPOSE 8095 8096
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1:8095/healthz >/dev/null || exit 1
CMD ["node", "server/server.js"]
