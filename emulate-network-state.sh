#!/bin/bash

echo "⏳ Resetting existing qdisc rules..."
docker exec -it node_one sh -c "tc qdisc del dev eth0 root netem 2>/dev/null || true"
docker exec -it node_two sh -c "tc qdisc del dev eth0 root netem 2>/dev/null || true"
docker exec -it node_three sh -c "tc qdisc del dev eth0 root netem 2>/dev/null || true"

echo "⚙️ Applying  network conditions..."

# node_one: light delay + small jitter + light loss
docker exec -it node_one sh -c "tc qdisc add dev eth0 root netem delay 100ms 20ms loss 2% 5%"

# node_two: medium delay + jitter + same loss
docker exec -it node_two sh -c "tc qdisc add dev eth0 root netem delay 120ms 30ms loss 2% 5%"

# node_three: slightly higher delay + jitter + same loss
docker exec -it node_three sh -c "tc qdisc add dev eth0 root netem delay 140ms 30ms loss 2% 5%"

echo "✅ Network conditions applied."
