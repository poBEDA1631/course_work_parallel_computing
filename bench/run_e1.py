"""E1: index build time against the number of build threads.

For every thread count, runs POST /api/index several times on a running server and records the
build time the server reports. The request is synchronous: it returns when the build is done.
One unrecorded warm-up build runs first, so that every measured build reads the dataset from a
warm page cache.
"""

import argparse
import csv
import json
import os
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
BUSY_RETRY_SECONDS = 5
# A single-threaded build of the full corpus takes a few minutes.
REQUEST_TIMEOUT_SECONDS = 1800
WARMUP_THREADS = 8


class Unreachable(Exception):
    pass


def build(base_url, threads):
    """Returns the JSON body of a successful build, or None if another build is running (409)."""
    url = base_url + "/api/index"
    request = urllib.request.Request(
        url,
        data=json.dumps({"threads": threads}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        if e.code == 409:
            return None
        message = json.loads(e.read() or b"{}").get("message", "")
        raise RuntimeError(f"POST {url} returned HTTP {e.code}: {message}") from e
    except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
        reason = getattr(e, "reason", e)
        raise Unreachable(f"Could not connect to {url}: {reason}") from e


def build_until_done(base_url, threads, label):
    """Repeats the same build while another one is running (409)."""
    while True:
        body = build(base_url, threads)
        if body is not None:
            return body
        print(f"{label}: another build is running, retrying", flush=True)
        time.sleep(BUSY_RETRY_SECONDS)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--url", default="http://localhost:8080", help="server base URL")
    parser.add_argument("--threads", default="1,2,4,8,16", help="comma-separated thread counts")
    parser.add_argument("--trials", type=int, default=3, help="runs per thread count")
    parser.add_argument("--out", default=os.path.join(HERE, "e1_build_threads.csv"))
    args = parser.parse_args()
    thread_counts = [int(t) for t in args.threads.split(",")]
    base_url = args.url.rstrip("/")

    warmup = build_until_done(base_url, WARMUP_THREADS, "warm-up")
    print(f"warm-up with {WARMUP_THREADS} threads: {warmup['time_ms']} ms (not recorded)",
          flush=True)

    with open(args.out, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["threads", "trial", "build_time_ms", "documents_indexed"])
        for threads in thread_counts:
            for trial in range(1, args.trials + 1):
                body = build_until_done(base_url, threads, f"threads={threads} trial={trial}")
                writer.writerow([threads, trial, body["time_ms"], body["documents_indexed"]])
                f.flush()
                print(f"threads={threads} trial={trial}: {body['time_ms']} ms, "
                      f"{body['documents_indexed']} documents", flush=True)
    print(f"wrote {args.out}")


if __name__ == "__main__":
    try:
        main()
    except (Unreachable, RuntimeError) as e:
        print(e, file=sys.stderr)
        sys.exit(1)
