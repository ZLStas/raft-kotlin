#!/bin/bash

NETWORK="raft-kotlin_default"
TARGET_NODE="node_two" # the slow follower
KEY_PREFIX="perf-key"
NUM_WRITES=20

echo "📦 Starting Raft performance test against $TARGET_NODE"

for i in $(seq 1 $NUM_WRITES); do
  key="${KEY_PREFIX}-${i}"
  value="value-${i}"

  # Measure start time in milliseconds (portable)
  start_time=$(gdate +%s%3N 2>/dev/null || date +%s%3N)

  docker run --rm --net=$NETWORK curlimages/curl \
    curl -s -o /dev/null -w "%{http_code}" -L -X POST http://$TARGET_NODE:8000/$key -d "$value" > /dev/null

  end_time=$(gdate +%s%3N 2>/dev/null || date +%s%3N)

  # Calculate duration
  duration=$((end_time - start_time))
  echo "⏱️  POST $key took ${duration}ms"
done

echo "✅ Writes complete."

echo
echo "🧪 Checking final values via node_one (assumed leader)"
for i in $(seq 1 $NUM_WRITES); do
  key="${KEY_PREFIX}-${i}"
  value=$(docker run --rm --net=$NETWORK curlimages/curl \
    curl -s -L http://node_one:8000/$key)
  echo "$key → $value"
done
