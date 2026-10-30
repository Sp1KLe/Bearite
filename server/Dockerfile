FROM node:20-alpine
WORKDIR /app
COPY package.json bearite-server.js ./
ENV PORT=8080
EXPOSE 8080
CMD ["node", "bearite-server.js"]
