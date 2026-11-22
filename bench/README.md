# Benchmarks

Scripts that measure a running server (`docker compose up`). They are not part of the Maven build.

## E1: build time against threads

```sh
python3 bench/run_e1.py                # POST /api/index for threads 1, 2, 4, 8, 16, 3 runs each
python3 -m venv .venv && .venv/bin/pip install -r bench/requirements.txt
.venv/bin/python bench/plot_e1.py      # reads the CSV, writes the PNG
```

- `run_e1.py` needs only the Python 3 standard library. `POST /api/index` returns when the build is
  done; the recorded time is the `time_ms` of that answer (the same value `GET /api/stats` shows as
  `index_build_time_ms`). It covers indexing the files, not listing the dataset directory.
- One warm-up build with 8 threads runs first and is not recorded: the first build after a start
  reads the dataset from a cold page cache and is much slower (in testing 74.6 s against about
  38 s afterwards), which would distort only the first measured point.
- Each thread count is run `--trials` times (default 3). If the server answers 409 because another
  build is running, the same run is retried every 5 s until it succeeds.
- Output: `bench/e1_build_threads.csv` with `threads,trial,build_time_ms,documents_indexed`,
  written row by row. Options: `--url` (default `http://localhost:8080`), `--threads`, `--trials`,
  `--out`.
- `plot_e1.py` writes `bench/e1_build_threads.png`: mean build time per thread count with the
  min-max range of the runs (options `--csv`, `--out`).
- Every build replaces the server's index; search is unavailable while the benchmark runs.

### Results in this directory

`e1_build_threads.csv` and `e1_build_threads.png` were measured on 2026-11-22 on a MacBook M1 Pro
with Docker Desktop given 8 vCPU and a 7.75 GB VM: the full corpus of 611,120 documents (1.5 GB),
the `search` container limited to 4.5 GB, the `llama3.2:3b` model loaded in `ollama`, one
unrecorded warm-up build (8 threads, 38.7 s), then 3 runs per thread count.

| Threads | Mean, s | Min-max, s | Speedup | Efficiency |
|---|---|---|---|---|
| 1 | 151.4 | 136.2-166.8 | 1.00 | 100 % |
| 2 | 80.9 | 79.6-82.6 | 1.87 | 94 % |
| 4 | 46.3 | 45.3-46.9 | 3.27 | 82 % |
| 8 | 28.7 | 28.2-29.1 | 5.28 | 66 % |
| 16 | 23.4 | 21.6-24.6 | 6.46 | 40 % |

They are reproducible on another machine with `run_e1.py` followed by `plot_e1.py`; absolute times
depend on the CPU, the disk and the Docker memory settings.

## E2: search throughput and latency against concurrent clients

```sh
.venv/bin/python bench/run_e2.py       # needs a built index (GET /api/stats must answer 200)
.venv/bin/python bench/plot_e2.py      # reads the CSV, writes the PNG
```

- Two paths: `POST /api/search` (BM25 only) and `/ws/search` (Ollama expansion, then BM25). Each
  is run with 1, 2, 4, 8, 16 and 32 clients, 20 s per configuration (`--clients`, `--window`).
- The whole sweep of 12 configurations is repeated 3 times (`--trials`), rather than repeating
  each configuration back to back, so that slow drift of the machine over the run shows up in
  the spread instead of hiding behind three almost identical neighbouring runs.
- Closed loop: every client is a thread that sends a query, waits for the complete answer and
  sends the next one at once. REST keeps one keep-alive connection per client; WebSocket opens a
  new connection per query, as the CLI and the Web UI do. Latency is measured by the client, for
  WebSocket from connecting to `done`.
- `failed` counts HTTP non-2xx answers and WebSocket `error` messages or dropped connections. A
  failed request is retried at once with the next query, so instant errors such as `server busy`
  can pile up. `throughput_rps` and the latency percentiles use successful requests only.
- A request still running when the window closes counts neither as completed nor as failed; it is
  reported in `in_flight_at_end`. The client waits for it (at most 60 s) before the next
  configuration starts, and pauses 5 s between configurations.
- Output: `bench/e2_throughput_latency.csv`, one row per run, with `path,clients,trial,completed,
  failed,in_flight_at_end,throughput_rps,latency_p50_ms,latency_p95_ms,latency_p99_ms,
  latency_min_ms,latency_max_ms,ws_expanded`, and `bench/e2_throughput_latency.png` with
  throughput and p95 latency per client count: the mean of the trials with the min-max range
  (both on logarithmic axes, since the two paths differ by orders of magnitude). `plot_e2.py` also
  prints this summary as a table.
- `ws_expanded` (WebSocket rows only) counts the successful requests whose `query_expansion`
  stage carried at least one term. The rest ran on the original query because Ollama did not
  answer within the server's 5 s expansion timeout (or, rarely, added nothing new). Without this
  column the WebSocket latency could be misread as the model's response time; the plot labels
  every WebSocket point with `expanded/completed`.

**Why the WebSocket path flattens out: Ollama generates one request at a time.** The `ollama`
service runs with the default `OLLAMA_NUM_PARALLEL=1`, so concurrent expansions queue inside
Ollama. One expansion takes about 2-3 s on 8 CPU cores, so with two or more clients the queue
alone exceeds the 5 s timeout: most requests then wait the full 5 s, get no expansion and search
the original query. Throughput is bounded by roughly (pool workers) / 5 s and latency by the 5 s
timeout plus waiting in the server's pool (8 workers, 16 queued; beyond 24 requests in flight the
server answers `server busy`). The limit is the model's parallelism, not BM25 or the pool.

**Worst case on purpose: a cold expansion cache.** Queries are generated from domain word lists
(adjectives, genres, topics, artists: about 310,000 distinct 2-3 word queries, shuffled with a
fixed seed) and no query is sent twice in a run, so the 256-entry LRU cache of query expansions is
never hit and every WebSocket request pays for a real Ollama call. Real traffic repeats
popular queries and would get noticeably better WebSocket throughput and latency from that cache.
A second run against the same server process starts with the previous run's queries in the
cache; restart the `search` service (and rebuild the index) to measure from a cold cache again.

### Results in this directory

`e2_throughput_latency.csv` and `e2_throughput_latency.png` were measured on 2026-11-22 on the
same MacBook M1 Pro as E1 (Docker Desktop with 8 vCPU and a 7.75 GB VM, `search` limited to
4.5 GB, `llama3.2:3b` loaded with `OLLAMA_NUM_PARALLEL=1`), against the full index of 611,120
documents. The `search` service was restarted and the index rebuilt right before the run; the
client ran on the same Mac (Python 3.9, outside the Docker VM). 3 trials of 20 s each.

| Clients | REST, req/s (min-max) | REST p95, ms | WS, req/s | WS p95, s | WS expanded / completed | WS failed |
|---|---|---|---|---|---|---|
| 1 | 208 (171-252) | 10.4 (8.0-12.4) | 0.28 | 4.37 (3.66-5.03) | 16 / 17 | 0 |
| 2 | 473 (448-488) | 8.8 (8.4-9.1) | 0.35 | 5.10 (5.08-5.12) | 7 / 21 | 0 |
| 4 | 883 (780-1056) | 9.8 (7.3-11.6) | 0.63 | 5.14 (5.10-5.19) | 5 / 38 | 0 |
| 8 | 1013 (589-1438) | 22.9 (10.9-38.7) | 1.23 | 5.15 (5.11-5.18) | 5 / 74 | 0 |
| 16 | 850 (442-1521) | 62.3 (22.9-90.5) | 1.25 | 10.18 (10.12-10.24) | 9 / 75 | 0 |
| 32 | 797 (465-1325) | 124.5 (55.7-181.0) | 1.20 | 15.28 (15.20-15.38) | 0 / 72 | 15,360 (`server busy`) |

- **WebSocket is set by the expansion timeout, and is deterministic.** From 2 clients on, the p95
  stays within 4 % of 5 s (2-8 clients), 10 s (16) and 15 s (32) in every trial: the 5 s
  timeout, plus one or two more 5 s turns of waiting in the pool queue. Almost every answer is
  expanded only with 1 client (16 of 17, p95 3.7-5.0 s, so even a single caller is close to the
  limit); with 2 clients a third are (7 of 21), with 4 or more 0-13 %. Ollama logged 51
  completed and 411 timed-out generate calls during the run.
- **REST scales up to the number of vCPUs** (about 8x the single-client throughput at 8
  clients in the first trial) with a p95 near 10 ms up to 4 clients, then queues for CPU: the
  p95 grows to about 60 ms at 16 and 125 ms at 32 clients.
- **REST beyond 4 clients drifted between the three sweeps**, not randomly: each sweep was slower
  than the one before (at 16 clients 1,521, then 589, then 442 req/s; low client counts stayed
  stable). The machine reported no thermal or performance warning; it ran on battery at first
  and was charging by the end. The cause was not isolated, so treat the REST numbers at 8 or
  more clients as a range for this machine, not as a single figure.

They are reproducible on another machine with `run_e2.py` followed by `plot_e2.py`; restart
`search` and rebuild the index first for a cold expansion cache. Absolute numbers depend on the
CPU, Docker settings and where the client runs.
