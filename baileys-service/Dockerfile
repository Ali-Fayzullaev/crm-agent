FROM node:20-alpine
RUN apk add --no-cache chromium nss freetype harfbuzz ca-certificates ttf-freefont
ENV PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium-browser
ENV CHROME_PATH=/usr/bin/chromium-browser
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY src/ ./src/
EXPOSE 3100
CMD ["node", "src/index.js"]
