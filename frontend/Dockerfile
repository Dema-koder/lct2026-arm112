# NODE_IMAGE can be overridden when Docker Hub is unreachable, e.g.:
#   docker compose build --build-arg NODE_IMAGE=arm112-training-frontend:latest frontend
ARG NODE_IMAGE=node:22-alpine
FROM ${NODE_IMAGE} AS build
USER root
WORKDIR /app

# Base may be a production image (NODE_ENV=production). Force a full install so
# build tools like vinext (devDependency) are present.
ENV NODE_ENV=development

ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080/api/v1
ARG NEXT_PUBLIC_WS_URL=ws://localhost:8080/ws/v1
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}
ENV NEXT_PUBLIC_WS_URL=${NEXT_PUBLIC_WS_URL}

COPY package.json package-lock.json ./
RUN npm ci --include=dev
COPY . .
RUN npm run build

ARG NODE_IMAGE=node:22-alpine
FROM ${NODE_IMAGE}
USER root
WORKDIR /app
ENV NODE_ENV=production
ENV HOST=0.0.0.0
ENV PORT=3000

COPY --from=build /app/package.json /app/package-lock.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY --from=build /app/.openai ./.openai

USER node
EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=10 \
    CMD wget --quiet --tries=1 --spider http://127.0.0.1:3000/ || exit 1
CMD ["npm", "run", "start", "--", "--hostname", "0.0.0.0"]
