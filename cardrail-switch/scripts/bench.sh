#!/usr/bin/env bash
# Benchmark harness for the switch's ISO 8583 path. It builds the switch and the load generator,
# starts the switch (dev profile, outbox published to the log, real PostgreSQL and Redis), opens a
# funded account for every card the load generator will use, runs one open-loop load test over TCP
# and prints the generator's report. It reports what it measured and draws no conclusions.
#
#   cardrail-switch/scripts/bench.sh [--rate 500] [--connections 4] [--duration 20s] [--warmup 5s]
#                                    [--cards 2000] [--seed 20261018] [--skip-build]
#
# Environment: JAVA_HOME (Homebrew's openjdk@21 if unset or not a JDK), BENCH_HTTP_PORT (28080),
# BENCH_ISO_PORT (28583), BENCH_SWITCH_ARGS (extra arguments for the switch, e.g. a datasource URL).
# The switch uses the dev database (cardrail) unless BENCH_SWITCH_ARGS points it elsewhere; every
# run adds its authorizations to it.
set -euo pipefail

RATE=500
CONNECTIONS=4
DURATION=20s
WARMUP=5s
CARDS=2000
SEED=20261018
BUILD=1
CURRENCY=356
# INR 10,00,000.00 per card, far above what a run can spend, so 51s come only from the risk rules'
# limits and not from the funding.
BALANCE_MINOR=100000000

while [[ $# -gt 0 ]]; do
    case "$1" in
        --rate) RATE=$2; shift 2 ;;
        --connections) CONNECTIONS=$2; shift 2 ;;
        --duration) DURATION=$2; shift 2 ;;
        --warmup) WARMUP=$2; shift 2 ;;
        --cards) CARDS=$2; shift 2 ;;
        --seed) SEED=$2; shift 2 ;;
        --skip-build) BUILD=0; shift ;;
        *) echo "bench.sh: unknown option $1" >&2; exit 2 ;;
    esac
done

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
    JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
export JAVA_HOME
JAVA="$JAVA_HOME/bin/java"
HTTP_PORT=${BENCH_HTTP_PORT:-28080}
ISO_PORT=${BENCH_ISO_PORT:-28583}
SWITCH_JAR="$ROOT/cardrail-switch/target/cardrail-switch-0.1.0-SNAPSHOT.jar"
LOADGEN_JAR="$ROOT/cardrail-loadgen/target/cardrail-loadgen-0.1.0-SNAPSHOT.jar"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/cardrail-bench.XXXXXX")
SWITCH_LOG="$WORK/switch.log"
SWITCH_PID=

stop_switch() {
    if [[ -n "$SWITCH_PID" ]] && kill -0 "$SWITCH_PID" 2>/dev/null; then
        kill -TERM "$SWITCH_PID"
        wait "$SWITCH_PID" 2>/dev/null || true
    fi
}
trap stop_switch EXIT

if [[ $BUILD -eq 1 ]]; then
    echo "== building switch and load generator"
    (cd "$ROOT" && mvn -q -B -o -pl cardrail-switch,cardrail-loadgen -am package -DskipTests)
fi

for port in "$HTTP_PORT" "$ISO_PORT"; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN > /dev/null 2>&1; then
        echo "bench.sh: port $port is in use; set BENCH_HTTP_PORT / BENCH_ISO_PORT" >&2
        exit 1
    fi
done

echo "== starting switch (log: $SWITCH_LOG)"
# shellcheck disable=SC2086
"$JAVA" -jar "$SWITCH_JAR" --spring.profiles.active=dev --cardrail.outbox.publisher=log \
    --server.port="$HTTP_PORT" --cardrail.iso.port="$ISO_PORT" ${BENCH_SWITCH_ARGS:-} \
    > "$SWITCH_LOG" 2>&1 &
SWITCH_PID=$!

deadline=$((SECONDS + 90))
until curl -sf "http://127.0.0.1:$HTTP_PORT/actuator/health/readiness" > /dev/null; do
    if ! kill -0 "$SWITCH_PID" 2>/dev/null; then
        echo "bench.sh: switch exited during startup; last log lines:" >&2
        tail -n 40 "$SWITCH_LOG" >&2
        exit 1
    fi
    if (( SECONDS > deadline )); then
        echo "bench.sh: switch not ready after 90 s" >&2
        exit 1
    fi
    sleep 0.5
done

echo "== opening $CARDS funded accounts for the load generator's cards (seed $SEED)"
"$JAVA" -cp "$LOADGEN_JAR" "$ROOT/cardrail-switch/scripts/BenchCards.java" \
    "$CARDS" "$SEED" "$BALANCE_MINOR" "$CURRENCY" > "$WORK/accounts.json"
curl -sf -X POST -H 'Content-Type: application/json' --data-binary @"$WORK/accounts.json" \
    "http://127.0.0.1:$HTTP_PORT/dev/accounts"
echo

echo "== environment"
echo "date:     $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "host:     $(uname -sm), $(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -p), $(getconf _NPROCESSORS_ONLN) CPUs"
echo "java:     $("$JAVA" -version 2>&1 | head -n 1)"
echo "postgres: $(psql -At -c 'SHOW server_version' -d postgres 2>/dev/null || echo unknown)"
echo "switch:   dev profile, outbox publisher=log, all services on this host"
echo "loadgen:  --rate $RATE --connections $CONNECTIONS --duration $DURATION --warmup $WARMUP --cards $CARDS --seed $SEED"

echo "== load"
"$JAVA" -jar "$LOADGEN_JAR" --host 127.0.0.1 --port "$ISO_PORT" --rate "$RATE" --connections "$CONNECTIONS" \
    --duration "$DURATION" --warmup "$WARMUP" --cards "$CARDS" --seed "$SEED" --currency "$CURRENCY" \
    --country 356

echo "== switch log: $SWITCH_LOG"
