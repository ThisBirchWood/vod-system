#!/bin/sh
set -eu

cat <<EOF > /usr/share/nginx/html/env-config.js
window._env_ = {
  VITE_API_URL: "${VITE_API_URL:-}",
  VITE_FRONTEND_URL: "${VITE_FRONTEND_URL:-}",
  VITE_GOOGLE_CLIENT_ID: "${VITE_GOOGLE_CLIENT_ID:-}"
};
EOF