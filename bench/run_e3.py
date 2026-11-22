"""E3: WebSocket search throughput against Ollama's parallelism (OLLAMA_NUM_PARALLEL).

Runs the WebSocket part of E2 (same closed-loop clients, same query pool and order) and labels
every row with the OLLAMA_NUM_PARALLEL the server's Ollama was started with. Ollama does not
report that setting over its API, so the caller passes it and is responsible for it being true.
Run once per setting; use --append for the second run.
"""

import argparse
import csv
import importlib.util
import itertools
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
_SPEC = importlib.util.spec_from_file_location("run_e2", os.path.join(HERE, "run_e2.py"))
e2 = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(e2)

FIELDS = ["num_parallel"] + e2.FIELDS


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--num-parallel", type=int, required=True,
                        help="OLLAMA_NUM_PARALLEL of the running ollama service (recorded only)")
    parser.add_argument("--url", default="http://localhost:8080", help="server base URL")
    parser.add_argument("--clients", default="1,2,4,8", help="comma-separated client counts")
    parser.add_argument("--window", type=float, default=20, help="seconds per configuration")
    parser.add_argument("--trials", type=int, default=3, help="repetitions of the whole sweep")
    parser.add_argument("--out", default=os.path.join(HERE, "e3_num_parallel.csv"))
    parser.add_argument("--append", action="store_true", help="add rows to an existing CSV")
    args = parser.parse_args()
    base_url = args.url.rstrip("/")

    stats = e2.require_index(base_url)
    print(f"index: {stats['documents']} documents; OLLAMA_NUM_PARALLEL={args.num_parallel}",
          flush=True)
    queries = e2.Queries(e2.query_pool(e2.SEED))

    with open(args.out, "a" if args.append else "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        if not args.append:
            writer.writeheader()
        for trial, clients in itertools.product(
                range(1, args.trials + 1), [int(c) for c in args.clients.split(",")]):
            row = e2.run_config("ws", clients, trial, args.window, base_url, queries)
            row["num_parallel"] = args.num_parallel
            writer.writerow(row)
            f.flush()
            print(f"num_parallel={args.num_parallel} trial {trial} clients={clients:2d}: "
                  f"{row['completed']} ok ({row['ws_expanded']} expanded), {row['failed']} failed, "
                  f"{row['throughput_rps']} rps, p95 {row['latency_p95_ms']} ms", flush=True)
            time.sleep(e2.PAUSE_BETWEEN_CONFIGS_SECONDS)
    print(f"wrote {args.out}")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as e:
        print(e, file=sys.stderr)
        sys.exit(1)
