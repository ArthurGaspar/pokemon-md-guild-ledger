# Build from the repository root (the client bundles ../shared):
#   docker build -f web/Dockerfile --build-arg API_URL=http://localhost:8080 .
FROM node:22-alpine AS build
ARG API_URL=http://localhost:8080
WORKDIR /src
COPY shared ./shared
COPY web/package.json web/package-lock.json ./web/
RUN cd web && npm ci
COPY web ./web
RUN cd web && VITE_API_URL=$API_URL npx vite build

FROM nginx:1.27-alpine
COPY --from=build /src/web/dist /usr/share/nginx/html
EXPOSE 80
