#!/bin/bash

# Real-World Network Delay Reference
#Network Type	Realistic One-Way Delay
#Local LAN (wired)	1–5 ms
#Same-region cloud (AWS, GCP)	2–10 ms
#Corporate VPN	10–40 ms
#Cross-country internet	30–80 ms
#Mobile 4G	50–100 ms
#Mobile 5G	20–50 ms (ideal), up to 100 ms
#Wi-Fi (home/office)	10–50 ms
#Satellite	300–600 ms (sometimes more)

# Jitter
#Wired LAN	1–5 ms (very low)
#Corporate WAN / VPN	5–20 ms
#Cloud-to-cloud (same region)	1–10 ms
#Public Internet (broadband)	10–50 ms
#Mobile 4G/5G (good signal)	30–100 ms
#Mobile (poor signal)	100–200+ ms
#Satellite Internet	300+ ms

echo "⏳ Resetting qdisc rules..."
for node in node_one node_two node_three; do
  docker exec -it $node sh -c "tc qdisc del dev eth0 root netem 2>/dev/null || true"
done

echo "⚙️ Applying  network conditions..."

# 🟢 node_one: light delay
docker exec -it node_one sh -c "tc qdisc add dev eth0 root netem delay 100ms 50ms"

# 🟡 node_two: medium delay
docker exec -it node_two sh -c "tc qdisc add dev eth0 root netem delay 120ms 50ms"

# 🔴 node_three: higher delay
docker exec -it node_three sh -c "tc qdisc add dev eth0 root netem delay 200ms 50ms"

echo "✅ Network conditions applied."
