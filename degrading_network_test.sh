#!/bin/bash

NETWORK="raft-kotlin_default"
CYCLE_DURATION=120    # Total cycle duration (seconds)
STEP_INTERVAL=10      # How often to change delay (seconds)
MAX_EXTRA_DELAY=300   # Max degradation added (ms)

# Base delays per node
BASE_DELAYS=(20 30 40)  # node_one, node_two, node_three

NODES=(node_three)

function log_time() {
  echo "🕒 $(date '+%Y-%m-%d %H:%M:%S') — $1"
}

function apply_netem() {
  for i in ${!NODES[@]}; do
    local delay=${BASE_DELAYS[$i]}
    local extra_delay=$1
    local total_delay=$((delay + extra_delay))
    log_time "🔧 Applying delay: ${total_delay}ms to ${NODES[$i]}"
    docker exec ${NODES[$i]} sh -c "tc qdisc replace dev eth0 root netem delay ${total_delay}ms 20ms"

    # ✅ Verify immediately after
    echo -n "🔍 Verifying: "
    docker exec ${NODES[$i]} sh -c "tc qdisc show dev eth0" | tee -a "$NETWORK_LOG"
  done
}

function reset_netem() {
  log_time "⏳ Resetting qdisc rules..."
  for i in ${!NODES[@]}; do
    docker exec ${NODES[$i]} sh -c "tc qdisc del dev eth0 root netem 2>/dev/null || true"
  done
}

function simulate_network_cycle() {
  log_time "🔁 Starting degradation/recovery cycle"
  start_time=$(date +%s)
  for ((t=0; t<$CYCLE_DURATION; t+=$STEP_INTERVAL)); do
    now=$(date +%s)
    elapsed=$((now - start_time))

    if (( t < CYCLE_DURATION / 2 )); then
      extra_delay=$(( (MAX_EXTRA_DELAY * t) / (CYCLE_DURATION / 2) ))
    else
      extra_delay=$(( (MAX_EXTRA_DELAY * (CYCLE_DURATION - t)) / (CYCLE_DURATION / 2) ))
    fi

    apply_netem $extra_delay
    log_time "⏱️  Elapsed: ${elapsed}s / ${CYCLE_DURATION}s"
    sleep $STEP_INTERVAL
  done
  log_time "✅ Cycle complete. Resetting delay to base."
  apply_netem 0
}

# MAIN
reset_netem
sleep 1
simulate_network_cycle
