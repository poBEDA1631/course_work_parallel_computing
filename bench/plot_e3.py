"""Plots E3: WebSocket throughput and share of expanded answers against clients, one line per
OLLAMA_NUM_PARALLEL value (mean over trials, min-max range). Prints the same summary as a table.
"""

import argparse
import csv
import os
from collections import defaultdict

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
COLORS = ["#3a3a3a", "#ff7a1a", "#2a7ab0", "#7a3ab0"]


def spread(values):
    mean = sum(values) / len(values)
    return mean, min(values), max(values)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--csv", default=os.path.join(HERE, "e3_num_parallel.csv"))
    parser.add_argument("--out", default=os.path.join(HERE, "e3_num_parallel.png"))
    args = parser.parse_args()

    runs = defaultdict(list)
    with open(args.csv, newline="") as f:
        for row in csv.DictReader(f):
            runs[(int(row["num_parallel"]), int(row["clients"]))].append(row)

    summary = {}
    for key, rows in runs.items():
        completed = [int(r["completed"]) for r in rows]
        expanded = [int(r["ws_expanded"]) for r in rows]
        p95 = [float(r["latency_p95_ms"]) for r in rows if r["latency_p95_ms"]]
        summary[key] = {
            "rps": spread([float(r["throughput_rps"]) for r in rows]),
            "share": spread([100 * e / c if c else 0.0 for e, c in zip(expanded, completed)]),
            "p95": spread(p95) if p95 else None,
            "expanded": sum(expanded),
            "completed": sum(completed),
        }

    settings = sorted({n for n, _ in summary})
    clients = sorted({c for _, c in summary})
    fig, (top, bottom) = plt.subplots(2, 1, figsize=(7, 7), sharex=True)
    for i, n in enumerate(settings):
        xs = [c for c in clients if (n, c) in summary]
        style = {"color": COLORS[i % len(COLORS)], "marker": "os^v"[i % 4], "capsize": 4,
                 "label": f"OLLAMA_NUM_PARALLEL={n}"}
        for ax, field in ((top, "rps"), (bottom, "share")):
            stats = [summary[(n, c)][field] for c in xs]
            ax.errorbar(xs, [m for m, _, _ in stats],
                        yerr=[[max(0.0, m - lo) for m, lo, _ in stats],
                              [max(0.0, hi - m) for m, _, hi in stats]], **style)

    trials = max(len(r) for r in runs.values())
    top.set_ylabel("WebSocket throughput, req/s")
    top.set_title(f"E3: Ollama parallelism ({trials} trials of 20 s, mean, min-max)")
    bottom.set_ylabel("answers with expansion, %")
    bottom.set_ylim(0, 105)
    bottom.set_xlabel("concurrent clients (closed loop)")
    for ax in (top, bottom):
        ax.set_xscale("log", base=2)
        ax.set_xticks(clients)
        ax.set_xticklabels([str(c) for c in clients])
        ax.grid(True, alpha=0.3)
        ax.legend(fontsize=9)
    top.set_ylim(bottom=0)
    fig.tight_layout()
    fig.savefig(args.out, dpi=150)

    print("num_parallel clients  rps mean (min-max)    expanded % mean (min-max)  expanded/completed"
          "  p95 s mean (min-max)")
    for (n, c), s in sorted(summary.items()):
        p95 = "{:.2f} ({:.2f}-{:.2f})".format(*(v / 1000 for v in s["p95"])) if s["p95"] else "-"
        print(f"{n:12d} {c:7d}  {'{:.2f} ({:.2f}-{:.2f})'.format(*s['rps']):20s} "
              f"{'{:.0f} ({:.0f}-{:.0f})'.format(*s['share']):26s} "
              f"{str(s['expanded']) + '/' + str(s['completed']):19s} {p95}")
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
