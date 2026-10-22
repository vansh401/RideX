# syntax=docker/dockerfile:1.7
#
# The React app: built with Node, SERVED by nginx. Two stages; the Node stage (about 400 MB of tools) is thrown away and only the
# built static files end up in the final image.
#
#   docker build -t ridex/web:dev frontend/ridex-web

# ---------------------------------------------------------------------------------------------------------------------------
# Stage 1: build the static site
# ---------------------------------------------------------------------------------------------------------------------------
FROM node:25-alpine AS build
WORKDIR /app

# Dependencies first: this layer is rebuilt only when package.json / package-lock.json change, not on every source edit
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund

COPY . .
# tsc type-checks, vite bundles and minifies into /app/dist
RUN npm run build

# ---------------------------------------------------------------------------------------------------------------------------
# Stage 2: serve it
# ---------------------------------------------------------------------------------------------------------------------------
# nginx-unprivileged: the official nginx build that runs as a normal user and listens on 8080 (ports below 1024 need root)
FROM nginxinc/nginx-unprivileged:1.28-alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8080
# the base image already runs as user "nginx" (uid 101), not root
