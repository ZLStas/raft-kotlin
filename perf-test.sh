#!/bin/bash

NETWORK="raft-kotlin_default"
TARGET_NODE="node_two"
KEY_PREFIX="perf-key"
NUM_WRITES=200

timestamp=$(date +"%Y%m%d_%H%M%S")
rm -f raft_benchmark_x.log
rm -f network_log_*.log
rm -f ./logs/node_*.log
OUT_FILE="raft_benchmark_x.log"
NETWORK_LOG="network_log_${timestamp}.log"

function log_time() {
  echo "🕒 $(date '+%Y-%m-%d %H:%M:%S') — $1"
}

START_TIME=$(date +%s)

log_time "📦 Starting Raft performance test against $TARGET_NODE" | tee -a "$OUT_FILE"

# Step 0: Clean System Info
echo -e "\n===============================" | tee -a "$OUT_FILE"
log_time "🖥️  Environment Overview" | tee -a "$OUT_FILE"
echo "===============================" | tee -a "$OUT_FILE"

{
  echo "🔹 Host System"
  echo "  OS: $(uname -s)"
  echo "  Kernel: $(uname -r)"
  echo "  Architecture: $(uname -m)"
  echo ""

  echo "🔹 Host Hardware"
  if [[ "$OSTYPE" == "darwin"* ]]; then
    echo "  CPU: $(sysctl -n machdep.cpu.brand_string)"
    echo "  Physical Cores: $(sysctl -n hw.physicalcpu)"
    echo "  Logical Cores: $(sysctl -n hw.logicalcpu)"
    memsize=$(sysctl -n hw.memsize 2>/dev/null || echo 0)
    echo "  Total RAM: $((memsize / 1024 / 1024)) MB"
    echo "  L2 Cache: $(( $(sysctl -n hw.l2cachesize) / 1024 )) KB"
    l3=$(sysctl -n hw.l3cachesize 2>/dev/null)
    l3=${l3:-0}
    [ "$l3" -gt 0 ] && echo "  L3 Cache: $((l3 / 1024)) KB"
  else
    echo "  CPU: $(lscpu | grep 'Model name' | cut -d: -f2 | xargs)"
    echo "  Physical Cores: $(lscpu | grep 'Core(s) per socket' | awk '{print $4}')"
    echo "  Logical Cores: $(nproc)"
    echo "  Total RAM: $(free -h | awk '/Mem:/ {print $2}')"
  fi
  echo ""

  echo "🔹 Docker Engine"
  echo "  Docker Version: $(docker version --format '{{.Server.Version}}')"
  echo "  Containers Running: $(docker ps -q | wc -l)"
  echo ""

  echo "🔹 Containers OS:"
  for node in node_one node_two node_three; do
    os_name=$(docker exec "$node" cat /etc/os-release 2>/dev/null | grep PRETTY_NAME | cut -d= -f2 | tr -d '"')
    echo "  - $node: $os_name"
  done
} >> "$OUT_FILE"

# Step 1: Apply network conditions in background with logging
echo -e "\n==============================="
log_time "🌐 Applying Network Conditions"
echo "==============================="
bash degrading_network_test.sh | tee "$NETWORK_LOG" &

# Step 2: Benchmark
echo -e "\n==============================="
log_time "🚀 Running Benchmark Writes"
echo "==============================="

declare -a latencies
total_time=0
success_count=0
fail_count=0
start_benchmark=$(date +%s)

echo -e "\n--- Request Timeline ---" >> "$OUT_FILE"

for i in $(seq 1 $NUM_WRITES); do
  key="${KEY_PREFIX}-${i}"
  value="value-${i}"
  req_time=$(date '+%Y-%m-%d %H:%M:%S.%3N')
  start_ms=$(gdate +%s%3N 2>/dev/null || date +%s%3N)
  http_code=$(docker run --rm --net=$NETWORK curlimages/curl \
    curl --max-time 5 -s -o /dev/null -w "%{http_code}" -X POST http://$TARGET_NODE:8000/$key -d "$value")
  end_ms=$(gdate +%s%3N 2>/dev/null || date +%s%3N)
  res_time=$(date '+%Y-%m-%d %H:%M:%S.%3N')
  duration=$((end_ms - start_ms))

  if [ "$http_code" -eq 200 ] || [ "$http_code" -eq 307 ]; then
    success_count=$((success_count + 1))
    latencies+=($duration)
    total_time=$((total_time + duration))
    echo "✅ $key | ${duration}ms"
    echo "$key | Req: $req_time | Res: $res_time | ${duration}ms | ✅" >> "$OUT_FILE"
  else
    fail_count=$((fail_count + 1))
    echo "❌ $key | ${duration}ms | HTTP $http_code"
    echo "$key | Req: $req_time | Res: $res_time | ${duration}ms | ❌ ($http_code)" >> "$OUT_FILE"
  fi
done

end_benchmark=$(date +%s)
benchmark_duration=$((end_benchmark - start_benchmark))
avg_latency=$((total_time / success_count))

# QoS
if (( success_count > 0 )); then
  sorted_latencies=($(printf '%s\n' "${latencies[@]}" | sort -n))
  index_99=$((success_count * 99 / 100))
  [ $index_99 -ge $success_count ] && index_99=$((success_count - 1))
  latency_99=${sorted_latencies[$index_99]}
  max_latency=${sorted_latencies[$((success_count - 1))]}
else
  latency_99=0
  max_latency=0
fi

sum_sq=0
for l in "${latencies[@]}"; do
  diff=$((l - avg_latency))
  sum_sq=$((sum_sq + diff * diff))
done
stddev=$(awk "BEGIN {printf \"%.2f\", sqrt($sum_sq / $success_count)}")

availability=$(awk "BEGIN {printf \"%.2f\", ($success_count / $NUM_WRITES) * 100}")
throughput=$(awk "BEGIN {printf \"%.2f\", $success_count / $benchmark_duration}")

echo -e "\n===============================" | tee -a "$OUT_FILE"
log_time "📊 QoS Summary" | tee -a "$OUT_FILE"
echo "===============================" | tee -a "$OUT_FILE"
{
  echo "✔️  Successful writes: $success_count"
  echo "❌ Failed writes:     $fail_count"
  echo "📈 Availability:      ${availability}%"
  echo "🚀 Throughput:        ${throughput} requests/sec"
  echo "⏱️  Avg Latency:      ${avg_latency}ms"
  echo "⏱️  P99 Latency:      ${latency_99}ms"
  echo "⏱️  Max Latency:      ${max_latency}ms"
  echo "📊 Std Dev Latency:   ${stddev}ms"
  echo "⏲️  Total Duration:    $((end_benchmark - START_TIME)) seconds"
} | tee -a "$OUT_FILE"

# Include network change log
echo -e "\n===============================" | tee -a "$OUT_FILE"
log_time "📶 Network Degradation Log" | tee -a "$OUT_FILE"
echo "===============================" | tee -a "$OUT_FILE"
cat "$NETWORK_LOG" | tee -a "$OUT_FILE"

echo "📊 Running Python analysis..."
# Activate virtual environment
source /Users/admin/bh/statistics/venv/bin/activate

# Run analysis
python chart.py
