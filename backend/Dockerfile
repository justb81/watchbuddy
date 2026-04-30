FROM node:25-alpine

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY src/ ./src/

# Run as non-root
USER node

EXPOSE 3000

ENV NODE_ENV=production

CMD ["node", "src/index.js"]
