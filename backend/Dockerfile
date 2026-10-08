# syntax=docker/dockerfile:1
# Phase 1: this only has to build and run. Production hardening is Phase 17.
ARG NODE_VERSION=22

FROM node:${NODE_VERSION}-alpine AS deps
# Prisma's query engine links against OpenSSL, which the Alpine image does not ship.
RUN apk add --no-cache openssl
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM deps AS build
COPY . .
# `prisma generate` only reads the schema; the URL is a placeholder and never contacted.
RUN DATABASE_URL=mongodb://localhost:27017/build npx prisma generate \
 && npm run build \
 && npm prune --omit=dev

FROM node:${NODE_VERSION}-alpine AS runtime
RUN apk add --no-cache openssl
ENV NODE_ENV=production
WORKDIR /app
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/dist ./dist
COPY --from=build --chown=node:node /app/prisma ./prisma
COPY --from=build --chown=node:node /app/package.json ./package.json
USER node
EXPOSE 3000
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT:-3000}/api/v1/health" >/dev/null || exit 1
CMD ["node", "dist/main.js"]
