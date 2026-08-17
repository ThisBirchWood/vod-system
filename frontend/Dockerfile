# --- Build stage ---
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npm run build

# --- Production stage ---
FROM nginx:1.27-alpine AS prod
COPY --from=build /app/dist /usr/share/nginx/html
COPY frontend_nginx.conf /etc/nginx/conf.d/default.conf

# Inject envs at runtime
COPY env.sh /docker-entrypoint.d/40-env.sh
RUN chmod +x /docker-entrypoint.d/40-env.sh

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]