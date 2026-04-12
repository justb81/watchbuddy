FROM node:20-alpine

WORKDIR /app

COPY package.json ./
RUN npm install --production

COPY src/ ./src/

RUN addgroup -S app && adduser -S app -G app
USER app

EXPOSE 3000

CMD ["node", "src/index.js"]
