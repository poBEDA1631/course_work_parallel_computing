"""E2: search throughput and latency against the number of concurrent clients.

Two paths of a running server with a built index: POST /api/search (BM25 only) and /ws/search
(Ollama expansion, then BM25). For every client count, each path gets a fixed window: that many
threads run a closed loop (send a query, wait for the complete answer, send the next one). The
whole sweep is repeated --trials times, so slow drift between runs shows up in the spread.
"""

import argparse
import csv
import http.client
import itertools
import json
import os
import random
import sys
import threading
import time
import urllib.parse

import websocket  # websocket-client, see requirements.txt

HERE = os.path.dirname(os.path.abspath(__file__))
REQUEST_TIMEOUT_SECONDS = 60
PAUSE_BETWEEN_CONFIGS_SECONDS = 5
SEED = 2026

ADJECTIVES = """classic old best funny sad dark live vintage epic quiet rare scary romantic
original acoustic instrumental holiday christmas modern early forgotten famous underrated
beautiful violent gentle strange silent loud cheap remastered complete restored animated
british french italian japanese american german spanish mexican irish russian korean indian
swedish brazilian cuban african canadian australian bootleg deluxe extended unreleased obscure
legendary haunting uplifting moody cozy""".split()

GENRES = """horror comedy western jazz blues rock opera documentary anime thriller romance
musical punk reggae gospel country classical drama war crime mystery fantasy soul funk metal
folk disco techno ambient swing bluegrass salsa flamenco noir sitcom cartoon ballet grunge
symphony concerto lullaby biopic satire slasher melodrama miniseries rockabilly motown ska
cabaret polka mambo tango bossa zydeco calypso doowop""".split()

TOPICS = """space aliens vampires zombies pirates cowboys piano guitar violin drums love ocean
robots dinosaurs detectives samurai ninjas monsters ghosts trains cars baseball boxing dance
wedding school prison island desert jungle mountains winter summer revenge heist spies magic
dragons kings soldiers sailors cats dogs horses witches angels gangsters lawyers doctors
nurses farmers hunters surfers astronauts orchestra choir saxophone trumpet cello harp banjo
accordion mandolin clarinet organ submarines volcanoes hurricanes castles circus carnival
casino rodeo railroads skyscrapers lighthouses ballerinas wrestlers truckers firefighters
cops outlaws gladiators vikings knights""".split()

ARTISTS = """john_wayne clint_eastwood elvis_presley frank_sinatra miles_davis bob_dylan
audrey_hepburn alfred_hitchcock johnny_cash louis_armstrong ella_fitzgerald david_bowie
marilyn_monroe charlie_chaplin bruce_lee meryl_streep tom_hanks dolly_parton
stevie_wonder aretha_franklin""".split()


def query_pool(seed):
    """Distinct 2-3 word queries built from domain words, shuffled reproducibly."""
    queries = set()
    queries.update(f"{a} {g}" for a in ADJECTIVES for g in GENRES)
    queries.update(f"{g} {t}" for g in GENRES for t in TOPICS)
    queries.update(f"{a} {g} {t}" for a in ADJECTIVES for g in GENRES for t in TOPICS)
    queries.update(f"{p.replace('_', ' ')} {a}" for p in ARTISTS for a in ADJECTIVES)
    pool = sorted(queries)
    random.Random(seed).shuffle(pool)
    return pool


class Queries:
    """Hands out each query once per run, to any thread."""

    def __init__(self, pool):
        self._it = iter(pool)
        self._lock = threading.Lock()
        self.size = len(pool)

    def next(self):
        with self._lock:
            try:
                return next(self._it)
            except StopIteration:
                raise RuntimeError(f"query pool of {self.size} exhausted; use a shorter window")


def rest_client(base_url):
    """Returns a callable that runs one query over a keep-alive connection.

    The callable returns (ok, None): ok on HTTP 2xx; REST has no expansion.
    """
    parts = urllib.parse.urlsplit(base_url)
    holder = {}

    def search(query):
        conn = holder.get("conn")
        if conn is None:
            conn = http.client.HTTPConnection(parts.hostname, parts.port or 80,
                                              timeout=REQUEST_TIMEOUT_SECONDS)
            holder["conn"] = conn
        body = json.dumps({"query": query, "mode": "keyword"})
        try:
            conn.request("POST", "/api/search", body, {"Content-Type": "application/json"})
            response = conn.getresponse()
            response.read()
            return 200 <= response.status < 300, None
        except (OSError, http.client.HTTPException):
            conn.close()
            holder.pop("conn", None)
            return False, None

    return search


def ws_client(base_url):
    """Returns a callable that runs one query on a new connection.

    The callable returns (ok, expanded): ok if the request ends with done, expanded if its
    query_expansion stage carried at least one term. An empty list means Ollama timed out, was
    unreachable or added nothing new, and the search ran on the original query.
    """
    ws_url = "ws" + base_url[len("http"):] + "/ws/search"

    def search(query):
        try:
            ws = websocket.create_connection(ws_url, timeout=REQUEST_TIMEOUT_SECONDS)
        except (OSError, websocket.WebSocketException):
            return False, False
        expanded = False
        try:
            ws.send(json.dumps({"query": query}))
            while True:
                message = json.loads(ws.recv())
                stage = message.get("stage")
                if stage == "query_expansion":
                    expanded = bool(message["data"]["expanded_terms"])
                elif stage == "done":
                    return True, expanded
                elif stage == "error":
                    return False, expanded
        except (OSError, ValueError, KeyError, websocket.WebSocketException):
            return False, expanded
        finally:
            ws.close()

    return search


def run_config(path, clients, trial, window, base_url, queries):
    """Runs `clients` closed loops for `window` seconds; returns one CSV row."""
    make = rest_client if path == "rest" else ws_client
    lock = threading.Lock()
    latencies_ms = []
    counts = {"failed": 0, "in_flight_at_end": 0, "expanded": 0}
    start_gate = threading.Barrier(clients + 1)
    end = [0.0]

    def loop():
        search = make(base_url)
        start_gate.wait()
        while time.perf_counter() < end[0]:
            query = queries.next()
            started = time.perf_counter()
            ok, expanded = search(query)
            finished = time.perf_counter()
            with lock:
                if finished > end[0]:
                    # Still running when the window closed: neither completed nor failed.
                    counts["in_flight_at_end"] += 1
                elif ok:
                    latencies_ms.append((finished - started) * 1000)
                    counts["expanded"] += 1 if expanded else 0
                else:
                    counts["failed"] += 1

    threads = [threading.Thread(target=loop, daemon=True) for _ in range(clients)]
    for t in threads:
        t.start()
    end[0] = time.perf_counter() + window
    start_gate.wait()
    for t in threads:
        t.join()

    latencies_ms.sort()

    def pct(p):
        if not latencies_ms:
            return ""
        return round(latencies_ms[min(len(latencies_ms) - 1, int(p / 100 * len(latencies_ms)))], 1)

    completed = len(latencies_ms)
    return {
        "path": path,
        "clients": clients,
        "trial": trial,
        "completed": completed,
        "failed": counts["failed"],
        "in_flight_at_end": counts["in_flight_at_end"],
        "throughput_rps": round(completed / window, 2),
        "latency_p50_ms": pct(50),
        "latency_p95_ms": pct(95),
        "latency_p99_ms": pct(99),
        "latency_min_ms": round(latencies_ms[0], 1) if latencies_ms else "",
        "latency_max_ms": round(latencies_ms[-1], 1) if latencies_ms else "",
        "ws_expanded": counts["expanded"] if path == "ws" else "",
    }


def require_index(base_url):
    parts = urllib.parse.urlsplit(base_url)
    conn = http.client.HTTPConnection(parts.hostname, parts.port or 80, timeout=10)
    try:
        conn.request("GET", "/api/stats")
        response = conn.getresponse()
        body = json.loads(response.read() or b"{}")
    except (OSError, http.client.HTTPException) as e:
        raise RuntimeError(f"Could not connect to {base_url}/api/stats: {e}") from e
    finally:
        conn.close()
    if response.status != 200:
        raise RuntimeError(f"no index to search: GET /api/stats returned HTTP {response.status}: "
                           f"{body.get('message', '')}; build it first (POST /api/index)")
    return body


FIELDS = ["path", "clients", "trial", "completed", "failed", "in_flight_at_end", "throughput_rps",
          "latency_p50_ms", "latency_p95_ms", "latency_p99_ms", "latency_min_ms",
          "latency_max_ms", "ws_expanded"]


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--url", default="http://localhost:8080", help="server base URL")
    parser.add_argument("--clients", default="1,2,4,8,16,32", help="comma-separated client counts")
    parser.add_argument("--window", type=float, default=20, help="seconds per configuration")
    parser.add_argument("--trials", type=int, default=3, help="repetitions of the whole sweep")
    parser.add_argument("--out", default=os.path.join(HERE, "e2_throughput_latency.csv"))
    args = parser.parse_args()
    base_url = args.url.rstrip("/")

    stats = require_index(base_url)
    print(f"index: {stats['documents']} documents", flush=True)
    queries = Queries(query_pool(SEED))
    print(f"query pool: {queries.size} distinct queries", flush=True)

    with open(args.out, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        writer.writeheader()
        for trial, clients, path in itertools.product(
                range(1, args.trials + 1), [int(c) for c in args.clients.split(",")],
                ["rest", "ws"]):
            row = run_config(path, clients, trial, args.window, base_url, queries)
            writer.writerow(row)
            f.flush()
            print(f"trial {trial} {path:4s} clients={clients:2d}: {row['completed']} ok, "
                  f"{row['failed']} failed, "
                  f"{row['in_flight_at_end']} in flight at end, {row['throughput_rps']} rps, "
                  f"p95 {row['latency_p95_ms']} ms"
                  + (f", {row['ws_expanded']} expanded" if path == "ws" else ""), flush=True)
            time.sleep(PAUSE_BETWEEN_CONFIGS_SECONDS)
    print(f"wrote {args.out}")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as e:
        print(e, file=sys.stderr)
        sys.exit(1)
