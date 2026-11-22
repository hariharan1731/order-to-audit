#!/usr/bin/env bash
# Records the section 5 benchmark demo video (captioned, no audio).
#
#   demo/record-demo.sh [output.webm]        default: target/demo/order-to-audit-benchmark-demo.webm
#
# Needs Docker running, Maven and Java 21+. It starts a throwaway PostgreSQL on port 55432 and the app on port 8099
# with the benchmark seed data, records about 5 minutes of video, then removes both. Your normal app (8080) and
# its database volume are not touched.
set -euo pipefail

cd "$(dirname "$0")/.."
OUT="${1:-$PWD/target/demo/order-to-audit-benchmark-demo.webm}"
PORT=8099
DB_PORT=55432
DB=orderaudit-demo-db
WORK=target/demo
mkdir -p "$WORK" "$(dirname "$OUT")"

for p in "$PORT" "$DB_PORT"; do
  if lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Port $p is already in use; free it and run again." >&2
    exit 1
  fi
done

cleanup() {
  local pids
  pids=$(lsof -nP -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true)
  [ -n "$pids" ] && kill $pids 2>/dev/null || true
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null || true
  docker rm -f "$DB" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Starting a throwaway database..."
docker rm -f "$DB" >/dev/null 2>&1 || true
docker run -d --name "$DB" -e POSTGRES_DB=orderaudit -e POSTGRES_USER=orderaudit \
  -e POSTGRES_PASSWORD=demo_only -p "$DB_PORT":5432 postgres:16-alpine >/dev/null
until docker exec "$DB" pg_isready -U orderaudit -d orderaudit >/dev/null 2>&1; do sleep 1; done

echo "Starting the app with the benchmark seed data on port $PORT..."
DB_URL="jdbc:postgresql://localhost:$DB_PORT/orderaudit" DB_USER=orderaudit DB_PASSWORD=demo_only \
  APP_SEED_ENABLED=true SERVER_PORT="$PORT" mvn -q spring-boot:run > "$WORK/app.log" 2>&1 &
APP_PID=$!
for _ in $(seq 1 90); do
  curl -sf -o /dev/null "http://localhost:$PORT/login" && break
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "The app stopped; see $WORK/app.log" >&2
    exit 1
  fi
  sleep 2
done
curl -sf -o /dev/null "http://localhost:$PORT/login" || { echo "The app did not start; see $WORK/app.log" >&2; exit 1; }

echo "Recording (about 5 minutes)..."
mvn -q dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt" -Dmdep.includeScope=test
java -cp "$(cat "$WORK/cp.txt")" demo/BenchmarkDemo.java "http://localhost:$PORT" "$OUT"
echo "Done: $OUT"
