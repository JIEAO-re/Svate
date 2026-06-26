# syntax=docker/dockerfile:1

FROM node:20-alpine AS deps
WORKDIR /app
COPY package*.json ./
RUN npm ci

FROM node:20-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=8080
ENV HOSTNAME=0.0.0.0
# Next.js standalone output (see next.config.ts) bundles only the runtime
# dependencies, so the image does not carry the full node_modules tree
# (vitest, eslint, and other dev tooling stay out of production).
COPY --from=builder --chown=node:node /app/.next/standalone ./
COPY --from=builder --chown=node:node /app/.next/static ./.next/static
COPY --from=builder --chown=node:node /app/public ./public
# Run as the unprivileged "node" user shipped with the official image.
USER node
EXPOSE 8080
CMD ["node", "server.js"]
