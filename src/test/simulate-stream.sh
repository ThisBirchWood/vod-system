#!/usr/bin/env bash

set -euo pipefail
 
if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <stream_key> [rtmp_base_url]" >&2
  exit 1
fi

KEY="$1"
BASE_URL="${2:-rtmp://localhost:1935/live}"

exec ffmpeg -hide_banner -loglevel info \
  -re \
  -f lavfi -i "testsrc2=size=1920x1080:rate=30" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -c:v libx264 -preset veryfast -tune zerolatency \
  -b:v 4500k -maxrate 4500k -bufsize 9000k \
  -pix_fmt yuv420p -g 60 -keyint_min 60 -sc_threshold 0 \
  -c:a aac -b:a 160k -ar 48000 -ac 2 \
  -f flv "${BASE_URL}/${KEY}"