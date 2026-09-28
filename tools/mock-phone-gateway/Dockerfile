FROM node:22-alpine

WORKDIR /app
COPY server.js ./server.js

USER node
EXPOSE 8090

HEALTHCHECK --interval=5s --timeout=2s --retries=10 \
  CMD wget --quiet --tries=1 --spider http://127.0.0.1:8090/health || exit 1

CMD ["node", "server.js"]
