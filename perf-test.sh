#!/bin/bash

NETWORK="raft-kotlin_default"
TARGET_NODE="node_two"
KEY_PREFIX="perf-key"
NUM_WRITES=10

timestamp=$(date +"%Y%m%d_%H%M%S")
OUT_FILE="raft_benchmark_${timestamp}.log"

echo "📦 Starting Raft performance test against $TARGET_NODE" | tee -a "$OUT_FILE"

# Step 1: Apply network conditions
echo "⏳ Applying network conditions..." | tee -a "$OUT_FILE"
bash emulate-network-state.sh | tee -a "$OUT_FILE"

# Optional: Allow the cluster to stabilize
echo "⏸️ Allowing the cluster to stabilize..."
sleep 10  # Adjust the duration as needed

# Step 2: Retrieve and store network settings
echo "🔍 Retrieving network settings..." | tee -a "$OUT_FILE"
for node in node_one node_two node_three; do
  echo "Settings for $node:" | tee -a "$OUT_FILE"
  docker exec -it $node tc qdisc show dev eth0 | tee -a "$OUT_FILE"
  echo "" | tee -a "$OUT_FILE"
done

# Initialize counters
total_time=0
success_count=0
fail_count=0

# Step 3: Perform benchmark tests
for i in $(seq 1 $NUM_WRITES); do
  key="${KEY_PREFIX}-${i}"
  value="value-${i}"

  start_time=$(gdate +%s%3N 2>/dev/null || date +%s%3N)

  http_code=$(docker run --rm --net=$NETWORK curlimages/curl \
    curl --max-time 5 -s -o /dev/null -w "%{http_code}" -L -X POST http://$TARGET_NODE:8000/$key -d "$value")

  end_time=$(gdate +%s%3N 2>/dev/null || date +%s%3N)
  duration=$((end_time - start_time))
  total_time=$((total_time + duration))

  if [ "$http_code" -eq 200 ]; then
    success_count=$((success_count + 1))
    echo "✅ POST $key took ${duration}ms" | tee -a "$OUT_FILE"
  else
    fail_count=$((fail_count + 1))
    echo "❌ POST $key failed (${http_code}), took ${duration}ms" | tee -a "$OUT_FILE"
  fi
done

# Calculate average time
avg_time=$((total_time / NUM_WRITES))

# Step 4: Compile benchmark summary
{
  echo "📊 Benchmark Summary:"
  echo "  ✔️  Successful writes: $success_count"
  echo "  ❌ Failed writes: $fail_count"
  echo "  ⏱️  Average write latency: ${avg_time}ms"
} | tee -a "$OUT_FILE"

echo "📁 Results saved to: $OUT_FILE"
