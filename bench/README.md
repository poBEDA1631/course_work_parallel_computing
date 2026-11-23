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

**Why the WebSocket path flattens out: the model's CPU inference speed.** One expansion takes
about 2-3 s of `llama3.2:3b` running on the CPU (8 vCPU). Ollama runs with the default
`OLLAMA_NUM_PARALLEL=1`, so concurrent expansions queue inside it, and with two or more clients
the queue alone exceeds the 5 s timeout: most requests then wait the full 5 s, get no expansion
and search the original query. Throughput is bounded by roughly (pool workers) / 5 s and latency
by the 5 s timeout plus waiting in the server's pool (8 workers, 16 queued; beyond 24 requests in
flight the server answers `server busy`). The queue is a symptom, not the cause: E3 below shows
that a second Ollama slot does not help, because both slots share the same CPU. The limit is the
model's inference throughput on the CPU (tokens per second), not the number of Ollama slots,
BM25 or the pool.

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

## E3: bottlenecks

### The M4 build-time anomaly

During M4 the index was built once with 8 threads in 74.6 s and then rebuilt with 4 threads in
82.9 s: doubling the threads seemed to gain almost nothing. Later measurements of the same thread
counts show that neither number measured the parallel build itself:

| Build | When and how | Time |
|---|---|---|
| 8 threads | M4, the first build after `dataset-init`: every file read from disk | 74.6 s |
| 8 threads | M5, the next 8-thread build, same code, files already in the page cache | 38.8 s |
| 4 threads | M4, a rebuild while the previous index was still alive (before `d929c32`) | 82.9 s |
| 4 threads | M7, a rebuild after `d929c32` released the old index first | 55.9 s |
| 4 / 8 threads | E1 (warm-up build, memory limit of `10677f6`), mean of 3 runs | 46.3 s / 28.7 s |

- The 8-thread number was inflated by a cold page cache: the first build after the corpus is
  written reads all 611,120 files from disk, the next one from memory. E1 therefore starts with an
  unrecorded warm-up build.
- The 4-thread rebuild was slowed by two complete indexes in the heap at once (about 1.9 GB
  each): the old one stayed in use until the new one was ready, so the garbage collector had far
  less room. `d929c32` releases the old index before a rebuild.
- With both effects removed, 4 to 8 threads gives 46.3 s to 28.7 s (1.6x), in line with the rest
  of E1. The memory limit of `10677f6` fixed a later, different problem: once the Ollama model
  stayed loaded (M6), an unbounded heap next to it got the container OOM-killed. It did not exist
  in M4, when the model was not kept in memory.

### Is Ollama's parallelism the bottleneck? (`OLLAMA_NUM_PARALLEL` 1 against 2)

E2 suggested that the WebSocket plateau comes from Ollama generating one request at a time. To
check it, the WebSocket part of E2 was run twice with the same clients, window and query order:
once with the project's `OLLAMA_NUM_PARALLEL=1` and once, temporarily, with 2. The project keeps
1; the setting was changed only for this run, through a Compose override file outside the
repository.

```sh
# baseline: the project's setting
docker compose restart search && curl -X POST localhost:8080/api/index -d '{"threads": 8}'
.venv/bin/python bench/run_e3.py --num-parallel 1

# two slots, from an override file (not part of the project)
printf 'services:\n  ollama:\n    environment:\n      OLLAMA_NUM_PARALLEL: "2"\n' > /tmp/np2.yml
docker compose -f docker-compose.yml -f /tmp/np2.yml up -d --force-recreate ollama ollama-init
docker compose restart search && curl -X POST localhost:8080/api/index -d '{"threads": 8}'
.venv/bin/python bench/run_e3.py --num-parallel 2 --append

# back to the project's setting
docker compose up -d --force-recreate ollama ollama-init
.venv/bin/python bench/plot_e3.py
```

- `run_e3.py` reuses E2's clients and query pool and adds a `num_parallel` column; Ollama does not
  report the setting over its API, so it is a label given by the caller. Before each run `search`
  is restarted (an empty expansion cache, since both runs send the same queries) and the model is
  warmed up again by `ollama-init`.
- Output: `bench/e3_num_parallel.csv` (the E2 columns plus `num_parallel`) and
  `bench/e3_num_parallel.png` with WebSocket throughput and the share of answers that carried an
  expansion, per client count and setting (mean of 3 trials, min-max). On the plot the share is the
  mean of the per-trial shares (with min-max), while the table below gives the share of the sums
  over the three trials, so the two can differ by a few points.

Results (2026-11-22, same machine as E1 and E2, 3 trials of 20 s):

| Clients | req/s, 1 slot | req/s, 2 slots | expanded, 1 slot | expanded, 2 slots | p95, 1 slot | p95, 2 slots |
|---|---|---|---|---|---|---|
| 1 | 0.43 (0.30-0.60) | 0.32 (0.20-0.40) | 24 / 26 (92 %) | 15 / 19 (79 %) | 3.56 s | 4.21 s |
| 2 | 0.43 (0.40-0.50) | 0.40 (0.40-0.40) | 21 / 26 (81 %) | 14 / 24 (58 %) | 5.04 s | 5.12 s |
| 4 | 0.67 (0.65-0.70) | 0.68 (0.65-0.70) | 10 / 40 (25 %) | 7 / 41 (17 %) | 5.22 s | 5.46 s |
| 8 | 1.30 (1.25-1.35) | 1.28 (1.25-1.30) | 7 / 78 (9 %) | 4 / 77 (5 %) | 5.13 s | 5.19 s |

- **A second slot does not help; it makes expansions slightly rarer at every client count.**
  Throughput is unchanged (it is still set by the 5 s timeout and the pool), and the share of
  expanded answers drops (81 % to 58 % at 2 clients). With two slots the successful Ollama calls
  took 3.8 s in the median, against the 2-3 s seen for single calls earlier (the M6 manual run
  and Ollama's log during E2; recreating the `ollama` container dropped the log of this
  baseline). The model runs entirely on the CPU (`ollama ps`: `100% CPU`), and two parallel
  generations split the same 8 vCPU, so each one is slower and fewer of them finish within 5 s.
- **The bottleneck is the model's inference throughput on the CPU, not Ollama's parallelism.**
  `OLLAMA_NUM_PARALLEL=1` only decides how that fixed capacity is shared; the queue in E2 exists
  because one expansion costs 2-3 s of CPU. Remedies would have to add compute or remove work:
  a GPU, a smaller model, shorter answers, a longer timeout, or cache hits on repeated queries.
- With two slots the model took 3.4 GB instead of 2.9 GB (a second KV cache), which next to the
  4.5 GB limit of `search` nearly fills the 7.75 GB VM; there was no OOM.
- The one-slot baseline expanded more often than the same points of E2 (81 % against 33 % at 2
  clients): in E2 every WebSocket window followed 20 s of REST load on the same CPU, here it did
  not. That is why E3 measures its own baseline instead of reusing E2's numbers.

## Search test cases

```sh
python3 bench/run_search_cases.py      # needs a built index; writes bench/search_cases.csv
```

- `run_search_cases.py` needs only the Python 3 standard library (the WebSocket client is a few
  lines over a socket). Each case states its expected result as a condition written in the script
  before the run; the script checks it and writes `pass` or `fail`. It exits with status 1 if any
  case fails.
- Whether a document contains a word is checked on the whole document, read from the `search`
  container with `docker compose exec`, not on the snippet (a snippet shows only the first
  occurrence of one word). The script tokenizes and stems the text with the same rules as
  `Tokenizer` and `Stemmer`.
- Output: `bench/search_cases.csv` with `case,query,request,expected,actual,verdict`. `actual`
  gives the HTTP status, the number of results, the server's `time_ms` and the top 3 `doc_id`s
  with a snippet fragment near a query word. Options: `--url` (default `http://localhost:8080`),
  `--compose-file`, `--out`.
- Query choice: `xylophonist` occurs in 7 documents, so the rare-term case is not cut at 10.
  `hemidemisemiquaver` is a real English word that the corpus does not contain. For the two-word
  case `jazz piano` would prove nothing, because all of its top 10 contain both words; in
  `xylophonist jazz` only 3 documents have both, so the top 10 mixes both kinds of documents.
- The empty query is a valid string with no indexed term, so it returns `200` with
  `"results": []`, as the README states for `POST /api/search`.
- The WebSocket case passes with an empty `expanded_terms` too: when Ollama is slow, the contract
  is to search the original query. In the run below the expansion came from the server's cache.
  The first `jazz piano` request timed out at 5 s while the host was loaded, and a later request
  filled the cache.

### Results in this directory

`search_cases.csv` was measured on 2026-11-23 on the machine from E1, against the full index of
611,120 documents built with 16 threads:

| Case | Query | Expected | Actual | Verdict |
|---|---|---|---|---|
| rare_term | `xylophonist` | 200, at least one result, the top-1 document contains the term | 7 results; top-1 `B0009K7R9I` contains it; 10 ms | pass |
| two_words | `xylophonist jazz` | the top 10 has documents with both words and with one only; all with both rank above all with one | both at ranks 1-3, one word only at ranks 4-10; 5 ms | pass |
| case_insensitive | `JAZZ Piano` | the same 10 doc_ids, order and scores as `jazz piano` | identical; top-1 `B003TLMUAK`; 8 ms | pass |
| stemming | `jazz albums` | the same 10 doc_ids, order and scores as `jazz album` | identical; top-1 `B007Y57174`; 27 ms | pass |
| repeated_term | `jazz jazz piano` | the same 10 doc_ids, order and scores as `jazz piano` | identical; 11 ms | pass |
| punctuation | `jazz, piano!` | the same 10 doc_ids, order and scores as `jazz piano` | identical; 10 ms | pass |
| missing_word | `hemidemisemiquaver` | 200 and `"results": []` | 200, 0 results; 2 ms | pass |
| empty_query | `""` | 200 and `"results": []` | 200, 0 results; 5 ms | pass |
| bad_mode | `jazz piano`, `"mode": "semantic"` | 400 and `{"status": "error", "message": string}` | 400, `"mode" must be "keyword"` | pass |
| normalization | `jazz piano` | top-1 score 1.0, last 0.0, scores never increase | 1.000, 0.987, 0.967, 0.885, 0.410, 0.390, 0.280, 0.152, 0.096, 0.000 | pass |
| common_word | `movie` | 200 and exactly 10 results | 10 results; 12 ms | pass |
| websocket | `jazz piano` over `/ws/search` | `query_expansion` -> `keyword_search` -> `done`; `original` equals the query; a result with all query words; every result has a query or expansion term | stages in that order; 6 expansion terms; all 10 results contain both words; 13 ms | pass |
