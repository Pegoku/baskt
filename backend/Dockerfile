FROM oven/bun:1.2-alpine AS deps
WORKDIR /app
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile --production

FROM oven/bun:1.2-alpine
WORKDIR /app
ENV NODE_ENV=production DATABASE_PATH=/data/baskt.db PORT=3000
COPY --from=deps /app/node_modules ./node_modules
COPY package.json tsconfig.json ./
COPY drizzle ./drizzle
COPY src ./src
VOLUME ["/data"]
EXPOSE 3000
CMD ["bun", "src/index.ts"]
