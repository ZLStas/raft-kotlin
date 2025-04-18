# Text-based chart for network delay over time

time_points = list(range(0, 121, 10))
delays = [20, 78, 136, 195, 253, 311, 370, 311, 253, 195, 136, 78, 20]

# Parameters
max_bar_length = 25  # max characters in the bar
max_delay = max(delays)
unit = max_delay / max_bar_length

print("📈 Network Delay Degradation and Recovery (node_three)\n")
print(f"{'Time (s)':>9} | {'Delay (ms)':>11} | Visual")
print("-" * 60)

for t, d in zip(time_points, delays):
    bar_length = int(d / unit)
    bar = "█" * bar_length
    print(f"{t:>9} | {d:>11} | {bar}")
