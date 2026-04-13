FROM node:22-alpine

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --production

COPY src/ ./src/

# Run as non-root
USER node

EXPOSE 3000

CMD ["node", "src/index.js"]
