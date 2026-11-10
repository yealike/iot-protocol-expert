#!/usr/bin/env bash
set -euo pipefail

BASE="$(cd "$(dirname "$0")" && pwd)"
JAVA="${JAVA:-java}"
PLATFORM_JAR="$BASE/iot-protocol-expert-protocol-1.0.0-exec.jar"
SIMULATOR_JAR="$BASE/iot-protocol-expert-simulator-1.0.0.jar"
LOG_DIR="$BASE/logs"
RUN_DIR="$BASE/run"

mkdir -p "$LOG_DIR" "$RUN_DIR"

start_one() {
  local name="$1" jar="$2" workdir="$3"
  local pidfile="$RUN_DIR/$name.pid"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    echo "$name 已在运行，PID $(cat "$pidfile")"
    return
  fi
  if [ ! -f "$jar" ]; then
    echo "缺少 $jar"
    exit 1
  fi
  echo "启动 $name ..."
  ( cd "$workdir"; nohup "$JAVA" -Xms256m -Xmx1024m -jar "$jar" >> "$LOG_DIR/$name.log" 2>&1 & echo $! > "$pidfile" )
  local pid port
  pid="$(cat "$pidfile")"
  port=19080; [ "$name" = simulator ] && port=19081
  for _ in $(seq 1 60); do
    if curl --fail --silent --max-time 2 "http://127.0.0.1:$port/actuator/health" >/dev/null; then
      echo "$name PID $pid，日志 $LOG_DIR/$name.log"
      return
    fi
    if ! kill -0 "$pid" 2>/dev/null; then break; fi
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "$name PID $pid，日志 $LOG_DIR/$name.log"
  fi
  echo "$name 未通过健康检查，请查看 $LOG_DIR/$name.log"
  exit 1
}

stop_one() {
  local name="$1"
  local pidfile="$RUN_DIR/$name.pid"
  if [ -f "$pidfile" ]; then
    local pid jar
    pid="$(cat "$pidfile")"
    jar="$SIMULATOR_JAR"; [ "$name" = protocol ] && jar="$PLATFORM_JAR"
    if [[ "$pid" =~ ^[0-9]+$ ]] && [ -r "/proc/$pid/cmdline" ] && tr '\0' '\n' < "/proc/$pid/cmdline" | grep -Fqx -- "$jar"; then
      kill "$pid"
      for _ in $(seq 1 30); do
        if ! kill -0 "$pid" 2>/dev/null || [ ! -r "/proc/$pid/cmdline" ] || ! tr '\0' '\n' < "/proc/$pid/cmdline" | grep -Fqx -- "$jar"; then break; fi
        sleep 1
      done
      if [ -r "/proc/$pid/cmdline" ] && tr '\0' '\n' < "/proc/$pid/cmdline" | grep -Fqx -- "$jar"; then
        echo "$name 未能正常退出，保留 PID 文件，请检查日志"
        exit 1
      fi
    fi
  fi
  rm -f "$pidfile"
  echo "$name 已停止"
}

case "${1:-start}" in
  start)
    mkdir -p "$BASE/data"
    start_one protocol "$PLATFORM_JAR" "$BASE"
    start_one simulator "$SIMULATOR_JAR" "$BASE"
    ;;
  stop)
    stop_one simulator
    stop_one protocol
    ;;
  restart)
    "$0" stop
    "$0" start
    ;;
  status)
    for name in protocol simulator; do
      pidfile="$RUN_DIR/$name.pid"
      if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
        echo "$name: running (PID $(cat "$pidfile"))"
      else
        echo "$name: stopped"
      fi
    done
    curl -s -m 3 http://127.0.0.1:19080/api/overview >/dev/null && echo "平台 HTTP: OK" || echo "平台 HTTP: 不可用"
    curl -s -m 3 http://127.0.0.1:19081/api/state >/dev/null && echo "模拟器 HTTP: OK" || echo "模拟器 HTTP: 不可用"
    ;;
  *)
    echo "用法: $0 {start|stop|restart|status}"
    exit 1
    ;;
esac
