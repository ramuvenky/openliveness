FROM node:20-alpine
WORKDIR /app
RUN npm install -g pnpm@9
COPY package.json ./
RUN pnpm install
COPY tsconfig.json ./
COPY src ./src
RUN pnpm build
ENV NODE_ENV=production
EXPOSE 3000
CMD ["node", "dist/server.js"]
