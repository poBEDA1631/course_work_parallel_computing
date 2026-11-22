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
