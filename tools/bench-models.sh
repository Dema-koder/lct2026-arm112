#!/usr/bin/env bash
# Прогон одного и того же замера по нескольким моделям.
#
# Для каждой: поднять сайдкар, дождаться готовности, прогнать LlmBenchmarkTest,
# погасить сайдкар. Результат каждой модели ложится в benchmarks/llm-<имя>.csv,
# сводка собирается tools/compare-models.py.
#
#   tools/bench-models.sh qwen2.5-7b-q4 qwen2.5-7b-q6 saiga-llama3-8b-q4
#
# Модели берутся из ./models/<имя>.gguf. Если файл ещё качается, скрипт ждёт,
# пока его размер перестанет расти, — иначе llama.cpp упадёт на обрезанном файле.
set -u

REPO="$(cd "$(dirname "$0")/.." && pwd)"
REPO_WIN="$(cd "$REPO" && pwd -W 2>/dev/null || echo "$REPO")"
M2_WIN="$(cd "$HOME" && pwd -W 2>/dev/null || echo "$HOME")/.m2"
PORT="${LLM_PORT:-8090}"
COUNT="${LLM_COUNT:-12}"
THREADS="${LLM_THREADS:-10}"

wait_stable() {
  local file="$1" prev=0 now
  while :; do
    [ -f "$file" ] || { sleep 20; continue; }
    now=$(stat -c %s "$file" 2>/dev/null || echo 0)
    # два одинаковых замера подряд с паузой — файл дописан
    [ "$now" = "$prev" ] && [ "$now" -gt 100000000 ] && return 0
    prev=$now
    sleep 30
  done
}

for model in "$@"; do
  file="$REPO/models/$model.gguf"
  echo "=============================================================="
  echo "  $model"
  echo "=============================================================="
  echo "-- жду готовности файла"
  wait_stable "$file"
  echo "-- размер: $(du -h "$file" | cut -f1)"

  docker rm -f llm-bench >/dev/null 2>&1
  MSYS_NO_PATHCONV=1 docker run -d --name llm-bench \
    -v "$REPO_WIN/models:/models:ro" -p "$PORT:8080" \
    ghcr.io/ggml-org/llama.cpp:server \
    -m "/models/$model.gguf" --host 0.0.0.0 --port 8080 -c 4096 -t "$THREADS" >/dev/null

  echo "-- жду, пока модель загрузится в память"
  ready=0
  for _ in $(seq 1 120); do
    curl -sf "http://localhost:$PORT/health" >/dev/null 2>&1 && { ready=1; break; }
    sleep 10
  done
  if [ "$ready" != "1" ]; then
    echo "-- ПРОПУСК: сервер не поднялся"
    docker logs llm-bench 2>&1 | tail -5
    docker rm -f llm-bench >/dev/null 2>&1
    continue
  fi

  MSYS_NO_PATHCONV=1 docker run --rm --network host \
    -v "$REPO_WIN:/workspace" -v "$M2_WIN:/root/.m2" -w /workspace \
    maven:3.9.9-eclipse-temurin-21 \
    mvn -B -o test -Dtest=LlmBenchmarkTest \
      "-Dllm.url=http://localhost:$PORT" "-Dllm.name=$model" "-Dllm.count=$COUNT" 2>&1 \
    | sed -n '/=== Скорость/,/CSV:/p'

  docker rm -f llm-bench >/dev/null 2>&1
done

echo
echo "Сводка: python tools/compare-models.py"
