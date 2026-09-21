FROM node:24-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && mkdir /app/data && chown -R node:node /app
COPY --chown=node:node src ./src
ENV HOST=0.0.0.0 PORT=8787 DATABASE_PATH=/app/data/family.sqlite
USER node
EXPOSE 8787
VOLUME ["/app/data"]
CMD ["node", "src/index.js"]
