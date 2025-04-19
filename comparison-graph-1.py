import matplotlib.pyplot as plt
import re
from datetime import datetime

# File paths
adaptive_log_path = "modified_raft_benchmark-2.log"
basic_log_path = "raft_benchmark_20250419_161956.log"

# Jitter value (ms)
JITTER_MS = 40

# Function to extract request time and latency
def extract_times_and_latencies(file_path):
    with open(file_path, "r") as file:
        content = file.read()
    matches = re.findall(r"Req:\s+(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+)N\s+\|\s+Res:.*?\|\s+(\d+)ms", content)
    return [(datetime.strptime(m[0], "%Y-%m-%d %H:%M:%S.%f"), int(m[1])) for m in matches]

# Function to extract delay changes
def extract_delay_changes(file_path):
    with open(file_path, "r") as file:
        content = file.read()
    pattern = r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d+)?)\s+[–—-]?\s+🔧 Applying delay: (\d+)ms to node_three"
    matches = re.findall(pattern, content)
    return [(datetime.strptime(m[0], "%Y-%m-%d %H:%M:%S.%f" if '.' in m[0] else "%Y-%m-%d %H:%M:%S"), int(m[1])) for m in matches]

# Convert datetime list to relative seconds
def to_relative_seconds(times):
    base = times[0]
    return [(t - base).total_seconds() for t in times]

# Extract data
basic_data = extract_times_and_latencies(basic_log_path)
adaptive_data = extract_times_and_latencies(adaptive_log_path)
basic_delay_data = extract_delay_changes(basic_log_path)
adaptive_delay_data = extract_delay_changes(adaptive_log_path)

# Trim to equal length
min_len = min(len(basic_data), len(adaptive_data))
basic_data = basic_data[:min_len]
adaptive_data = adaptive_data[:min_len]

# Split and convert
basic_times, basic_latencies = zip(*basic_data)
adaptive_times, adaptive_latencies = zip(*adaptive_data)
basic_seconds = to_relative_seconds(basic_times)
adaptive_seconds = to_relative_seconds(adaptive_times)

basic_delay_times, basic_delay_values = zip(*basic_delay_data) if basic_delay_data else ([], [])
adaptive_delay_times, adaptive_delay_values = zip(*adaptive_delay_data) if adaptive_delay_data else ([], [])
basic_delay_seconds = to_relative_seconds(basic_delay_times) if basic_delay_times else []
adaptive_delay_seconds = to_relative_seconds(adaptive_delay_times) if adaptive_delay_times else []

# --- Plot Setup ---
fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(16, 10), sharex=True)

# --- Basic Raft ---
ax1.plot(basic_seconds, basic_latencies, label="Базовий Raft", marker='o', markersize=3, color='blue')
ax1.axhline(sum(basic_latencies) / len(basic_latencies), color='blue', linestyle='--', linewidth=1, label='Середнє значення')
ax1.set_title("Затримка базового Raft (відносний час виконання)")
ax1.set_ylabel("Затримка (мс)")
ax1.grid(True)
ax1.legend(loc='upper left')

# Basic Delay + Jitter
ax1_delay = ax1.twinx()
if basic_delay_seconds:
    lower = [v - JITTER_MS for v in basic_delay_values]
    upper = [v + JITTER_MS for v in basic_delay_values]
    ax1_delay.fill_between(basic_delay_seconds, lower, upper, step='post', alpha=0.2, color='green', label="Джитер (±20мс)")
    ax1_delay.step(basic_delay_seconds, basic_delay_values, label="Затримка", color='green', linewidth=1.5, where='post')
    ax1_delay.set_ylabel("Затримка на node_three (мс)")
    ax1_delay.tick_params(axis='y', labelcolor='green')
    ax1_delay.legend(loc='upper right')

# --- Adaptive Raft ---
ax2.plot(adaptive_seconds, adaptive_latencies, label="Адаптивний Raft", marker='x', markersize=3, color='orange')
ax2.axhline(sum(adaptive_latencies) / len(adaptive_latencies), color='orange', linestyle='--', linewidth=1, label='Середнє значення')
ax2.set_title("Затримка модифікованого Raft (відносний час виконання)")
ax2.set_xlabel("Час (секунди від початку)")
ax2.set_ylabel("Затримка (мс)")
ax2.grid(True)
ax2.legend(loc='upper left')

# Adaptive Delay + Jitter
ax2_delay = ax2.twinx()
if adaptive_delay_seconds:
    lower = [v - JITTER_MS for v in adaptive_delay_values]
    upper = [v + JITTER_MS for v in adaptive_delay_values]
    ax2_delay.fill_between(adaptive_delay_seconds, lower, upper, step='post', alpha=0.2, color='red', label="Джитер (±20мс)")
    ax2_delay.step(adaptive_delay_seconds, adaptive_delay_values, label="Затримка", color='red', linewidth=1.5, where='post')
    ax2_delay.set_ylabel("Затримка на node_three (мс)")
    ax2_delay.tick_params(axis='y', labelcolor='red')
    ax2_delay.legend(loc='upper right')

plt.tight_layout()
plt.show()
