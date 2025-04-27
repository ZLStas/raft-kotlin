import re
import pytz
import matplotlib.pyplot as plt
import pandas as pd
from datetime import datetime
from dateutil import parser

kyiv_tz = pytz.timezone("Europe/Kyiv")

# Файли
adaptive_log_path = "./results/raft_benchmark_x.log"
basic_log_path = "raft_benchmark_basic.log"
log_paths = {
    "node_one": "./results/node_50.log",
    "node_three": "./results/node_52.log",
}
JITTER_MS = 40

# --- Утиліти ---
def extract_times_and_latencies(file_path):
    with open(file_path, "r") as file:
        content = file.read()
    pattern = r"Req:\s+(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+)N\s+\|\s+Res:.*?\|\s+(\d+)ms\s+\|(?:\s+)?(✅|❌)?"
    matches = re.findall(pattern, content)
    return [
        (
            datetime.strptime(m[0], "%Y-%m-%d %H:%M:%S.%f"),
            int(m[1]),
            m[2] == '❌'
        )
        for m in matches
    ]

def extract_delay_changes(file_path):
    with open(file_path, "r") as file:
        content = file.read()
    pattern = r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d+)?)\s+[–—-]?\s+🔧 Applying delay: (\d+)ms to node_three"
    matches = re.findall(pattern, content)
    return [
        (
            parser.parse(m[0]),
            int(m[1])
        )
        for m in matches
    ]

def to_relative_seconds(times):
    base = times[0]
    return [(t - base).total_seconds() for t in times], base

# --- Витяг бенчмарків ---
basic_data = extract_times_and_latencies(basic_log_path)
adaptive_data = extract_times_and_latencies(adaptive_log_path)
basic_delay_data = extract_delay_changes(basic_log_path)
adaptive_delay_data = extract_delay_changes(adaptive_log_path)

min_len = min(len(basic_data), len(adaptive_data))
basic_data, adaptive_data = basic_data[:min_len], adaptive_data[:min_len]

basic_times, basic_latencies, basic_errors = zip(*basic_data)
adaptive_times, adaptive_latencies, adaptive_errors = zip(*adaptive_data)

basic_seconds, basic_base = to_relative_seconds(basic_times)
adaptive_seconds, adaptive_base = to_relative_seconds(adaptive_times)

basic_delay_times, basic_delay_values = zip(*basic_delay_data) if basic_delay_data else ([], [])
adaptive_delay_times, adaptive_delay_values = zip(*adaptive_delay_data) if adaptive_delay_data else ([], [])
basic_delay_seconds, _ = to_relative_seconds(basic_delay_times) if basic_delay_times else ([], [])
adaptive_delay_seconds, _ = to_relative_seconds(adaptive_delay_times) if adaptive_delay_times else ([], [])

# --- Обчислення спільних меж для затримок ---
combined_min_latency = min(min(basic_latencies), min(adaptive_latencies))
combined_max_latency = max(max(basic_latencies), max(adaptive_latencies))
y_margin = 0.05 * (combined_max_latency - combined_min_latency)
shared_ylim = (combined_min_latency - y_margin, combined_max_latency + y_margin)

# --- Парсинг логів вузлів ---
node_data = []
pattern = re.compile(r"\[(.*?)\] Interval update for Node: (\d+)\s+→\s+(\d+)")

for node_name, log_path in log_paths.items():
    with open(log_path, "r") as file:
        content = file.read()
    for match in pattern.findall(content):
        timestamp_str, node, interval = match
        timestamp = parser.parse(timestamp_str.strip())
        if timestamp.tzinfo is None:
            timestamp = kyiv_tz.localize(timestamp)
        else:
            timestamp = timestamp.astimezone(kyiv_tz)

        adaptive_base_kyiv = kyiv_tz.localize(adaptive_base) if adaptive_base.tzinfo is None else adaptive_base.astimezone(kyiv_tz)
        relative_time = (timestamp - adaptive_base_kyiv).total_seconds()

        node_data.append({
            "timestamp": timestamp,
            "relative_seconds": relative_time,
            "node": int(node),
            "node_name": node_name,
            "interval": int(interval),
        })

df_nodes = pd.DataFrame(node_data)

# --- Графіки ---
fig, axs = plt.subplots(3, 1, figsize=(16, 14), sharex=True)

# Basic Raft
axs[0].plot(basic_seconds, basic_latencies, color='gray', linewidth=0.5, alpha=0.5)
axs[0].scatter(basic_seconds, basic_latencies, c=['r' if err else 'g' for err in basic_errors], s=8)
axs[0].axhline(sum(basic_latencies)/len(basic_latencies), color='blue', linestyle='--', label='Середнє значення')
axs[0].set_title("Затримка базового Raft")
axs[0].set_ylabel("Затримка (мс)")
axs[0].set_ylim(shared_ylim)
axs[0].legend()
axs[0].grid(True)
if basic_delay_seconds:
    ax0_delay = axs[0].twinx()
    lower = [v - JITTER_MS for v in basic_delay_values]
    upper = [v + JITTER_MS for v in basic_delay_values]
    ax0_delay.fill_between(basic_delay_seconds, lower, upper, step='post', alpha=0.2, color='green')
    ax0_delay.step(basic_delay_seconds, basic_delay_values, color='green', where='post')
    ax0_delay.set_ylabel("Затримка node_three (мс)", color='green')

# Adaptive Raft
axs[1].plot(adaptive_seconds, adaptive_latencies, color='gray', linewidth=0.5, alpha=0.5)
axs[1].scatter(adaptive_seconds, adaptive_latencies, c=['r' if err else 'g' for err in adaptive_errors], s=8)
axs[1].axhline(sum(adaptive_latencies)/len(adaptive_latencies), color='orange', linestyle='--', label='Середнє значення')
axs[1].set_title("Затримка модифікованого Raft")
axs[1].set_ylabel("Затримка (мс)")
axs[1].set_ylim(shared_ylim)
axs[1].legend()
axs[1].grid(True)
if adaptive_delay_seconds:
    ax1_delay = axs[1].twinx()
    lower = [v - JITTER_MS for v in adaptive_delay_values]
    upper = [v + JITTER_MS for v in adaptive_delay_values]
    ax1_delay.fill_between(adaptive_delay_seconds, lower, upper, step='post', alpha=0.2, color='red')
    ax1_delay.step(adaptive_delay_seconds, adaptive_delay_values, color='red', where='post')
    ax1_delay.set_ylabel("Затримка node_three (мс)", color='red')

# Interval Evolution
for node_name, group in df_nodes.groupby("node_name"):
    axs[2].plot(group["relative_seconds"], group["interval"], marker="o", linestyle="-", label=f"{node_name}")
axs[2].set_title("Еволюція інтервалів по вузлах (адаптивна шкала часу)")
axs[2].set_xlabel("Час (секунди від початку адаптивного виконання)")
axs[2].set_ylabel("Інтервал (мс)")
axs[2].legend()
axs[2].grid(True)

plt.tight_layout()
# Save the plot instead of showing it
plt.savefig('raft_benchmark_results.png')
print("Plot saved as 'raft_benchmark_results.png'")
