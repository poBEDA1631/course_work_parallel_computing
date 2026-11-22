"""Plots E1: mean index build time per thread count, with the min-max range of the trials."""

import argparse
import csv
import os
from collections import defaultdict

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", default=os.path.join(HERE, "e1_build_threads.csv"))
    parser.add_argument("--out", default=os.path.join(HERE, "e1_build_threads.png"))
    args = parser.parse_args()

    seconds = defaultdict(list)
    with open(args.csv, newline="") as f:
        for row in csv.DictReader(f):
            seconds[int(row["threads"])].append(int(row["build_time_ms"]) / 1000)
    threads = sorted(seconds)
    means = [sum(seconds[t]) / len(seconds[t]) for t in threads]
    below = [m - min(seconds[t]) for t, m in zip(threads, means)]
    above = [max(seconds[t]) - m for t, m in zip(threads, means)]

    fig, ax = plt.subplots(figsize=(7, 4.5))
    ax.errorbar(threads, means, yerr=[below, above], marker="o", capsize=5,
                color="#ff7a1a", ecolor="#555555")
    for t, m in zip(threads, means):
        # Right of the point, so the label never sits on the min-max bar.
        ax.annotate(f"{m:.1f} s", (t, m), textcoords="offset points", xytext=(8, 4),
                    ha="left", fontsize=9)
    ax.set_xscale("log", base=2)
    ax.set_xticks(threads)
    ax.set_xticklabels([str(t) for t in threads])
    # Room for the label right of the last point.
    ax.set_xlim(threads[0] / 1.3, threads[-1] * 1.6)
    # Headroom for the labels above the highest point.
    ax.set_ylim(0, max(max(v) for v in seconds.values()) * 1.12)
    ax.set_xlabel("build threads")
    ax.set_ylabel("build time, s (mean, min-max)")
    trials = max(len(v) for v in seconds.values())
    ax.set_title(f"E1: index build time, {trials} trials per thread count")
    ax.grid(True, alpha=0.3)
    fig.tight_layout()
    fig.savefig(args.out, dpi=150)
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
