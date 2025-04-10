#!/bin/bash

NETWORK="raft-kotlin_default"
TARGET_NODE="node_two"
KEY_PREFIX="perf-key"
NUM_WRITES=1000

timestamp=$(date +"%Y%m%d_%H%M%S")
OUT_FILE="raft_benchmark_${timestamp}.log"

echo "📦 Starting Raft performance test against $TARGET_NODE" | tee -a "$OUT_FILE"

# Step 0: Clean System Info
echo -e "\n===============================" | tee -a "$OUT_FILE"
echo "🖥️  Environment Overview" | tee -a "$OUT_FILE"
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
    echo "  Total RAM: $(($(sysctl -n hw.memsize) / 1024 / 1024)) MB"
    echo "  L2 Cache: $(($(sysctl -n hw.l2cachesize) / 1024)) KB"
    l3=$(sysctl -n hw.l3cachesize 2>/dev/null || echo 0)
    [ "$l3" -gt 0 ] && echo "  L3 Cache: $(($l3 / 1024)) KB"
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

# Step 1: Apply network conditions (but don't write to file)
echo -e "\n==============================="
echo "🌐 Applying Network Conditions"
echo "==============================="
bash emulate-network-state.sh

echo "⏸️  Waiting 10s for cluster to stabilize..."
sleep 10

# Step 2: Show network qdisc settings
echo -e "\n===============================" | tee -a "$OUT_FILE"
echo "🔍 Current Network Settings" | tee -a "$OUT_FILE"
echo "===============================" | tee -a "$OUT_FILE"
for node in node_one node_two node_three; do
  echo -e "\n📡 $node" | tee -a "$OUT_FILE"
  docker exec "$node" tc qdisc show dev eth0 2>/dev/null | tee -a "$OUT_FILE"
done

# Step 3: Benchmark (only visible in console)
echo -e "\n==============================="
echo "🚀 Running Benchmark Writes"
echo "==============================="

total_time=0
success_count=0
fail_count=0

for i in $(seq 1 $NUM_WRITES); do
  key="${KEY_PREFIX}-${i}"
  value="value-${i}"

  start_time=$(gdate +%s%3N 2>/dev/null || date +%s%3N)
  http_code=$(docker run --rm --net=$NETWORK curlimages/curl \
    curl --max-time 5 -s -o /dev/null -w "%{http_code}" -X POST http://$TARGET_NODE:8000/$key -d "$value")
  end_time=$(gdate +%s%3N 2>/dev/null || date +%s%3N)
  duration=$((end_time - start_time))
  total_time=$((total_time + duration))

if [ "$http_code" -eq 200 ] || [ "$http_code" -eq 307 ]; then
    success_count=$((success_count + 1))
    echo "✅ $key | ${duration}ms"
  else
    fail_count=$((fail_count + 1))
    echo "❌ $key | ${duration}ms | HTTP $http_code"
  fi
done

# Step 4: Summary
avg_time=$((total_time / NUM_WRITES))

echo -e "\n===============================" | tee -a "$OUT_FILE"
echo "📊 Benchmark Summary" | tee -a "$OUT_FILE"
echo "===============================" | tee -a "$OUT_FILE"
{
  echo "✔️  Successful writes: $success_count"
  echo "❌ Failed writes:     $fail_count"
  echo "⏱️  Avg. write latency: ${avg_time}ms"
} | tee -a "$OUT_FILE"

echo -e "\n📁 Results saved to: $OUT_FILE"
