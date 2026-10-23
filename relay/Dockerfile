# Multi-stage build. The builder has pnpm, the full devDependency set, the
# TypeScript sources and emits dist/. The runtime image copies only dist/ and
# the production node_modules into a fresh node:20-alpine so the shipped image
# doesn't carry tsx, vitest, typescript or the TS sources themselves — anything
# we don't ship is one less thing to CVE-patch or exfiltrate from a running
# container.

FROM node:20-alpine AS builder
WORKDIR /app
RUN npm install -g pnpm@9
COPY package.json ./
RUN pnpm install
COPY tsconfig.json ./
COPY src ./src
RUN pnpm build

FROM node:20-alpine AS runtime
WORKDIR /app
RUN npm install -g pnpm@9
COPY package.json ./
# --prod drops devDependencies (typescript, tsx, vitest, supertest). We get a
# node_modules that only resolves the dependencies the running server actually
# imports.
RUN pnpm install --prod && npm uninstall -g pnpm
COPY --from=builder /app/dist ./dist
ENV NODE_ENV=production
# Drop root. node:20-alpine ships a built-in `node` user; running the server
# as that uid means a compromise inside the container can't chown /keys or
# write to /app.
USER node
EXPOSE 3000
CMD ["node", "dist/server.js"]
