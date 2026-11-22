"""Plots E2: throughput and p95 latency against concurrent clients, REST and WebSocket paths.

Each point is the mean over the trials with the min-max range as an error bar. Prints the same
summary as a table.
"""

import argparse
import csv
import os
from collections import defaultdict

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
STYLE = {
    "rest": {"label": "POST /api/search (BM25)", "color": "#ff7a1a", "marker": "o"},
    "ws": {"label": "/ws/search (Ollama + BM25)", "color": "#3a3a3a", "marker": "s"},
}


def summarize(rows):
    """Per (path, clients): mean/min/max of throughput and p95, and summed counters."""
    groups = defaultdict(list)
    for row in rows:
        groups[(row["path"], int(row["clients"]))].append(row)
    summary = {}
    for key, runs in groups.items():
        rps = [float(r["throughput_rps"]) for r in runs]
        p95 = [float(r["latency_p95_ms"]) for r in runs if r["latency_p95_ms"]]
        summary[key] = {
            "trials": len(runs),
            "rps": (sum(rps) / len(rps), min(rps), max(rps)),
            "p95": (sum(p95) / len(p95), min(p95), max(p95)) if p95 else None,
            "completed": sum(int(r["completed"]) for r in runs),
            "failed": sum(int(r["failed"]) for r in runs),
            "expanded": sum(int(r["ws_expanded"]) for r in runs if r["ws_expanded"] != ""),
        }
    return summary


def errorbar(ax, xs, stats, style):
    means = [m for m, _, _ in stats]
    # max(0, ...): with identical trials the float mean can land a hair outside [min, max].
    below = [max(0.0, m - lo) for m, lo, _ in stats]
    above = [max(0.0, hi - m) for m, _, hi in stats]
    ax.errorbar(xs, means, yerr=[below, above], capsize=4, **style)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--csv", default=os.path.join(HERE, "e2_throughput_latency.csv"))
    parser.add_argument("--out", default=os.path.join(HERE, "e2_throughput_latency.png"))
    args = parser.parse_args()

    with open(args.csv, newline="") as f:
        summary = summarize(csv.DictReader(f))
    clients = sorted({c for _, c in summary})

    fig, (top, bottom) = plt.subplots(2, 1, figsize=(7, 8), sharex=True)
    for path, style in STYLE.items():
        xs = [c for c in clients if (path, c) in summary]
        errorbar(top, xs, [summary[(path, c)]["rps"] for c in xs], style)
        # p95 exists only where at least one request succeeded.
        with_p95 = [c for c in xs if summary[(path, c)]["p95"]]
        errorbar(bottom, with_p95, [summary[(path, c)]["p95"] for c in with_p95], style)
        for c in xs:
            s = summary[(path, c)]
            if path == "ws":
                # Most WebSocket requests may have run without expansion (Ollama timed out).
                top.annotate(f"{s['expanded']}/{s['completed']} expanded", (c, s["rps"][0]),
                             textcoords="offset points", xytext=(-4, 9), fontsize=8,
                             color=style["color"], ha="center")
            if s["failed"]:
                top.annotate(f"{s['failed']} failed", (c, s["rps"][0]),
                             textcoords="offset points", xytext=(6, -13), fontsize=8,
                             color="#cc3333")

    trials = max(s["trials"] for s in summary.values())
    # The two paths differ by orders of magnitude, so both y axes are logarithmic.
    top.set_yscale("log")
    top.set_ylabel("throughput, successful requests/s")
    top.set_title(f"E2: search under concurrent clients ({trials} trials of 20 s, mean, min-max)")
    bottom.set_yscale("log")
    bottom.set_ylabel("p95 latency of successful requests, ms")
    bottom.set_xlabel("concurrent clients (closed loop)")
    for ax in (top, bottom):
        ax.set_xscale("log", base=2)
        ax.set_xticks(clients)
        ax.set_xticklabels([str(c) for c in clients])
        ax.grid(True, which="both", alpha=0.3)
        ax.legend(loc="center left", fontsize=9)
    fig.tight_layout()
    fig.savefig(args.out, dpi=150)

    print("path clients  rps mean (min-max)        p95 ms mean (min-max)      expanded/completed failed")
    for (path, c), s in sorted(summary.items()):
        rps = "{:.2f} ({:.2f}-{:.2f})".format(*s["rps"])
        p95 = "{:.1f} ({:.1f}-{:.1f})".format(*s["p95"]) if s["p95"] else "-"
        expanded = f"{s['expanded']}/{s['completed']}" if path == "ws" else "-"
        print(f"{path:4s} {c:7d}  {rps:24s} {p95:27s} {expanded:18s} {s['failed']}")
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
