#!/bin/bash

NETWORK="raft-kotlin_default"
TARGET_NODE="node_two"  # The slow follower
KEY_PREFIX="perf-key"
NUM_WRITES=100

timestamp=$(date +"%Y%m%d_%H%M%S")
OUT_FILE="raft_benchmark_${timestamp}.log"

echo "📦 Starting Raft performance test against $TARGET_NODE"

total_time=0
success_count=0
fail_count=0

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
    echo "✅ POST $key took ${duration}ms"
  else
    fail_count=$((fail_count + 1))
    echo "❌ POST $key failed (${http_code}), took ${duration}ms"
  fi
done

avg_time=$((total_time / NUM_WRITES))

# Save only the summary to file
{
  echo "📊 Benchmark Summary:"
  echo "  ✔️  Successful writes: $success_count"
  echo "  ❌ Failed writes: $fail_count"
  echo "  ⏱️  Average write latency: ${avg_time}ms"
} > "$OUT_FILE"

# Print summary to terminal
cat "$OUT_FILE"

# Optional: also validate values if needed
# for i in $(seq 1 $NUM_WRITES); do
#   key="${KEY_PREFIX}-${i}"
#   value=$(docker run --rm --net=$NETWORK curlimages/curl \
#     curl -s -L http://node_one:8000/$key)
#   echo "$key → $value"
# done

echo "📁 Results saved to: $OUT_FILE"
