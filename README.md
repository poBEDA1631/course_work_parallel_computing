# Parallel Search Engine

A containerised full-text search service. It builds an inverted index in parallel,
serves queries from many clients concurrently and expands queries through an
[Ollama](https://ollama.com) model.

## Stack

- Java 21, Maven (wrapper included)
- Javalin 6 (HTTP and WebSocket), Jackson
- Docker Compose, Ollama

## Running

```sh
docker compose up -d --build
curl localhost:8080/api/health
```

Then open http://localhost:8080 for the Web UI, or use the command-line client below.

On the first start `ollama-init` downloads the `llama3.2:3b` model (about 2 GB) and loads it.
Until it is available, `/api/health` reports `"ollama": false`; the backend does not wait for it.

The host port can be changed with the `PORT` variable, e.g. `PORT=9090 docker compose up -d`.

Services:

| Service | Role |
|---|---|
| `ollama` | Ollama server, models are kept in the `ollama-models` volume |
| `ollama-init` | one-shot `ollama pull llama3.2:3b` and a warm-up run of the expansion prompt, then exits |
| `dataset-init` | one-shot corpus download and preparation into the `dataset` volume, then exits |
| `search` | backend and static Web UI, starts after `dataset-init` completes; restarts after a crash (without an index) |

## Requirements and known limits

**Memory and disk.** The Docker VM must hold the `search` container, capped at 4.5 GB
(`mem_limit` in `docker-compose.yml`), next to the loaded `llama3.2:3b` model in `ollama`,
measured at 2.9 GB ([E3](bench/README.md#e3-bottlenecks)). Everything in this repository was
measured with Docker Desktop given 8 vCPU and a 7.75 GB VM; with less memory a container can be
killed for running out of it. On disk: the corpus, 1.5 GB in the `dataset` volume
([Dataset](#dataset)), the model, about 2 GB in `ollama-models` ([Running](#running)), and the
images (`docker images` shows their size).

**First start.** `search` waits until `dataset-init` has exited successfully, so on a fresh
clone `/api/health` does not answer until the corpus is ready (about 15 minutes in testing,
see [Dataset](#dataset)). The model is pulled at the same time and is not waited for: `search`
answers with `"ollama": false` until it is loaded. To follow the corpus download:

```sh
docker compose logs -f dataset-init
```

Later starts download nothing: `dataset-init` finds `.complete` in the volume, logs `dataset
already complete in /data, nothing to do` and exits.

**Duration of `POST /api/index`.** There is no index until the first build, and the request
answers only when the build is finished. On the full corpus that took from 151.4 s with 1 thread
down to 23.4 s with 16 threads on the E1 machine (MacBook M1 Pro, 8 vCPU, 7.75 GB VM; see
[E1](bench/README.md#e1-build-time-against-threads)), so the client has to wait long enough:

```sh
curl --max-time 600 -X POST localhost:8080/api/index \
  -H 'Content-Type: application/json' -d '{"threads": 8}'
```

A smaller corpus builds faster: set `DATASET_MAX_BYTES` (bytes, default `1500000000`). It is read
only while the corpus is being prepared, so once `.complete` exists the `dataset` volume has to be
removed first. `docker compose down -v` removes it, together with the downloaded model:

```sh
docker compose down -v
DATASET_MAX_BYTES=300000000 docker compose up -d --build
```

To keep the model, remove only the corpus volume, named after the project directory
(`course_work_parallel_computing_dataset` for a default clone):
`docker compose down && docker volume rm course_work_parallel_computing_dataset`.

**Dataset source.** The corpus is downloaded from the Amazon Reviews 2023 server
(`https://mcauleylab.ucsd.edu/public_datasets/data/amazon_2023/raw/`, the `review_categories/`
and `meta_categories/` files). A dropped connection is resumed (see [Dataset](#dataset)). If the
server cannot be reached at all, or a dropped download still fails after 5 resumes in a row,
`dataset-init` exits with status 1, and `search` does not start: it depends on `dataset-init` with
`service_completed_successfully`. In that case check the cause and run `docker compose up -d`
again later:

```sh
docker compose logs dataset-init
```

A failed run does not write `.complete`. The files it has written stay in the volume until the
next run, which deletes them and builds the corpus again from the beginning, downloading
everything anew.

## Dataset

Source: [Amazon Reviews 2023](https://amazon-reviews-2023.github.io) (McAuley Lab, UCSD),
categories `Movies_and_TV` and `CDs_and_Vinyl` (review and meta `jsonl.gz` files).

`dataset-init` (`scripts/fetch_dataset.py`, image `dataset-init/Dockerfile`) turns them into one
English text file per product, `<DATASET_DIR>/<parent_asin>.txt`: the product title, its
description and the title and text of its reviews. The document id is the file name without
`.txt`.

- Review files are streamed and only read until each category's half of the size budget is
  filled, so they are never downloaded in full; meta files are streamed to attach titles and
  descriptions.
- The budget is `DATASET_MAX_BYTES` (default `1500000000`, about 1.5 GB) and counts the final
  files. Products that do not fit are dropped whole.
- A dropped connection (network failure, the host going to sleep) is resumed with an HTTP
  `Range` request from the last byte read, up to 5 times in a row with growing pauses.
- On success `.complete` is written into the directory. If it exists, later runs do nothing;
  if it is missing (an interrupted run), the directory's `.txt` files are deleted and the corpus
  is built again.

The resume logic has standard-library tests (there is no CI for the Python scripts; run them by
hand):

```sh
python3 -m unittest discover -s scripts/tests -v
```

The first start downloads both meta files in full (about 0.5 GB compressed) and the beginning of
both review files, and took about 15 minutes in testing; the result is about 611,000 documents,
1.5 GB of text.

## Web UI

`src/main/resources/static/` (plain HTML, CSS and JavaScript, no build step), served at `/`.

- The header badge shows `Ollama: online` or `offline` from `GET /api/health`, polled every 5 s.
- The index block builds or rebuilds the index (`POST /api/index` with the chosen number of
  threads, 1 to 256) and shows the statistics from `GET /api/stats`. While a build runs, started
  from this page or elsewhere, it shows `Building...` and waits for the build to finish.
- Each search opens its own WebSocket to `/ws/search` and shows the stages as they arrive:
  `Expanding query...`, the expansion terms as badges (or `No expansion.`), then the results with
  scores and snippets (or `No results.`). Errors appear in a red banner. Starting a new search
  closes the previous connection and ignores its late messages.
- Text from the server, including snippets from the documents, is inserted as text only, never as
  HTML.

## Command-line client

`com.coursework.search.cli.SearchCli` is a thin client for `/ws/search` on a running server; it
does no indexing or ranking itself.

```sh
./search jazz piano
./search "good movies about space"
```

`./search` runs the client inside the running `search` container (`docker compose exec`), so the
host needs only Docker, not Java. It connects to the server's own port; `--host` and `--port`
override the target. All other words form one query.

With Java 21 on the host the client can also be run from the sources (default target
`localhost:8080`):

```sh
./mvnw -q compile exec:java -Dexec.mainClass="com.coursework.search.cli.SearchCli" -Dexec.args="jazz piano"
```

```text
Expanding query...
Expanded with: improvisation, classical, blues, swing, solo, instrumental

Results (10 found, 18 ms):
1. [1.00] B000005GZH
   like either one of them, you will be thrilled to own this CD, Thank you. Wonderful instrumental piano ...
2. [0.95] B0013RDZXI
   Mike Garson's Jazz Hat Classical Music Nice little Jazz album Mike Garson is most famous for being ...
```

Exit code `0` after a complete answer, including `No results.`; `1` with a message on stderr for
a server error, no server, no answer within 30 s, or a missing query or bad option (usage is
printed and nothing is sent). If the stack is not up, `./search` fails with Docker's
`service "search" is not running`.

## Screenshots and diagrams

Screenshots (`docs/`, taken against the full corpus of 611,120 documents):

- [Search results](docs/web_ui_search_results.png): the Web UI after a search for "Kanye west",
  with the expansion terms from Ollama as badges and the ten ranked results with scores and
  snippets.
- [Index building](docs/web_ui_index_building.png): a build with 8 threads in progress; the
  button is disabled and shows `Building...` while the previous results stay on the page.
- [Index rebuilt](docs/web_ui_index_rebuilt.png): the statistics of the finished rebuild
  (8 threads, 44.8 s) and the same query answered again with the same expansion.
- [Search during a build](docs/web_ui_build_in_progress_error.png): a search sent while the
  index is being rebuilt, answered with the red `index build in progress` banner.
- [No results](docs/web_ui_no_results.png): a query with no indexed term, shown as
  `No expansion.` and `No results.` rather than as an error.
- [Command-line client](docs/cli_output.png): `./search kanye west` in a terminal, printing the
  expansion and the ranked results as the stages arrive.

Diagrams (rendered SVG in `docs/`, each next to its PlantUML source):

- [Use cases](docs/use_case.svg) ([source](docs/use_case.puml)): what a user does through the
  Web UI, the CLI or the API, and which use cases depend on Ollama.
- [Building the index](docs/sequence_build_index.svg)
  ([source](docs/sequence_build_index.puml)): `POST /api/index` from `ApiServer`
  through `IndexService` and `IndexBuilder` to the thread pool and the per-shard merge.
- [Keyword search](docs/sequence_search_keyword.svg)
  ([source](docs/sequence_search_keyword.puml)): `POST /api/search` from parsing the body
  through `ExpandedQuery`, `Bm25Scorer`, the index, `TopKSelector` and `SnippetBuilder` to the
  answer, with the `400` and `409` branches.
- [Health and statistics](docs/sequence_health_stats.svg)
  ([source](docs/sequence_health_stats.puml)): `GET /api/health` checking Ollama through
  `GET /api/tags` with a 1 s timeout (`"ollama": false` when it is slow, unreachable or has no
  model), and `GET /api/stats` with its `409` while a build runs or before the first one.
- [Search with expansion](docs/sequence_search_expansion.svg)
  ([source](docs/sequence_search_expansion.puml)): a `/ws/search` request when
  Ollama answers in time, through the three stages `query_expansion`, `keyword_search`, `done`.
- [Search when Ollama fails](docs/sequence_search_expansion_fallback.svg)
  ([source](docs/sequence_search_expansion_fallback.puml)): the same request when
  Ollama times out, is unreachable or answers with an error, and the search runs on the original
  query.
- [Deployment](docs/deployment.svg) ([source](docs/deployment.puml)): the four Compose services,
  their volumes, the network and the start-up dependencies.

Class diagrams, one per package (rendered SVG with the PlantUML source):

- [concurrent](docs/class_concurrent.svg) ([source](docs/class_concurrent.puml)): `ThreadPool`
  with its bounded ring-buffer queue, worker threads and `Task`/`TaskHandle`.
- [index](docs/class_index.svg) ([source](docs/class_index.puml)): `InvertedIndex` and its
  shards, `LocalAccumulator`, `Postings` and the `Fnv1a` hash.
- [indexing](docs/class_indexing.svg) ([source](docs/class_indexing.puml)): `DocumentLoader`,
  `IndexBuilder` and `BuildResult`, and how they use the pool and the index.
- [query](docs/class_query.svg) ([source](docs/class_query.puml)): `KeywordSearch`,
  `Bm25Scorer`, `ExpandedQuery`, `TopKSelector` and `SnippetBuilder`.
- [ollama](docs/class_ollama.svg) ([source](docs/class_ollama.puml)): `QueryExpansionService`,
  its LRU `QueryExpansionCache` and `OllamaClient`.
- [api](docs/class_api.svg) ([source](docs/class_api.puml)): `ApiServer`, `IndexService` and
  `SearchSocket`, and the packages they depend on.

## Building locally

Requires JDK 21.

```sh
./mvnw verify
java -jar target/search.jar
```

`mvn verify` fails if any source file is not formatted with google-java-format.
To fix formatting run `./mvnw com.spotify.fmt:fmt-maven-plugin:format`.

## API

### `GET /api/health`

Response `200`:

| Field | Type | Description |
|---|---|---|
| `status` | string | `"ok"` while the service is up |
| `ollama` | boolean | whether Ollama answered `GET /api/tags` within 1 s and lists the configured model |

```json
{"status": "ok", "ollama": true}
```

Ollama is checked on every call. The status is `200` even when Ollama is down or the model has
not been pulled yet; only `ollama` changes.

### `POST /api/index`

Builds a new index over every `.txt` file in `DATASET_DIR` and responds when the build is
finished. The previous index, if any, is released when the build starts: two complete indexes
(about 1.9 GB of heap each for the full corpus) do not fit in memory next to the loaded Ollama
model. While the build runs, and after a failed build, there is no index.

Request:

| Field | Type | Description |
|---|---|---|
| `threads` | integer | worker threads, `1` to `256` |

Response `200`:

| Field | Type | Description |
|---|---|---|
| `status` | string | `"ok"` |
| `documents_indexed` | integer | documents in the new index (unreadable files are skipped) |
| `time_ms` | integer | build time in milliseconds |

```json
{"status": "ok", "documents_indexed": 611120, "time_ms": 74611}
```

Errors: `400` for a malformed body or `threads` out of range, `409` if a build is already
running, `500` if the dataset cannot be read.

### `GET /api/stats`

Statistics of the current index.

Response `200`:

| Field | Type | Description |
|---|---|---|
| `documents` | integer | documents in the index |
| `index_build_time_ms` | integer | build time in milliseconds |
| `threads_used` | integer | worker threads used for the build |

```json
{"documents": 611120, "index_build_time_ms": 74611, "threads_used": 8}
```

Errors: `409` with `"index build in progress"` while a build runs, or `"index has not been built
yet"` if there is no index.

### `POST /api/search`

BM25 keyword search over the current index.

Request:

| Field | Type | Description |
|---|---|---|
| `query` | string | free text, tokenized and stemmed like the documents |
| `mode` | string | must be `"keyword"` |

Response `200`:

| Field | Type | Description |
|---|---|---|
| `results` | array | at most 10 results, best first; empty if no query term is indexed |
| `results[].doc_id` | string | document id (`parent_asin`) |
| `results[].score` | number | BM25 score min-max normalized over the returned results: best `1.0`, worst `0.0`, all `1.0` if equal |
| `results[].snippet` | string | about 200 characters around the first literal occurrence of a query word, or the start of the document |
| `time_ms` | integer | search time in milliseconds |

```json
{
  "results": [
    {"doc_id": "B01CH6SA74", "score": 1.0, "snippet": "IMAX: Journey to Space Brilliantly narrated by ..."},
    {"doc_id": "B074QWGFSK", "score": 0.573, "snippet": "A Year in Space and Beyond a Year in Space ..."}
  ],
  "time_ms": 3
}
```

Errors: `400` for a malformed body, a non-string `query` or a `mode` other than `"keyword"`;
`409` with `"index build in progress"` while a build runs, or `"index has not been built yet"`.

### WebSocket `/ws/search`

Search with query expansion, answered in stages. A connection handles one request at a time;
every request ends with exactly one `done` or one `error` message.

Client message:

| Field | Type | Description |
|---|---|---|
| `query` | string | free text |

Server messages, in order:

1. `query_expansion`

   | Field | Type | Description |
   |---|---|---|
   | `stage` | string | `"query_expansion"` |
   | `data.original` | string | the query as sent |
   | `data.expanded_terms` | array of string | expansion keywords that add a stem the query does not have; `[]` when Ollama is unavailable, slow or adds nothing |

2. `keyword_search`

   | Field | Type | Description |
   |---|---|---|
   | `stage` | string | `"keyword_search"` |
   | `data.results` | array | same objects as `POST /api/search` (`doc_id`, `score`, `snippet`) |
   | `data.time_ms` | integer | search time in milliseconds |

3. `done`: `{"stage": "done"}`

```json
{"stage": "query_expansion", "data": {"original": "jazz piano", "expanded_terms": ["improvisation", "classical", "blues", "swing", "solo", "instrumental"]}}
{"stage": "keyword_search", "data": {"results": [{"doc_id": "B000005GZH", "score": 1.0, "snippet": "like either one of them, you will be thrilled to own this CD, Thank you. Wonderful instrumental piano ..."}, ...], "time_ms": 44}}
{"stage": "done"}
```

Ranking: the user's terms have weight 1.0, expansion stems not already in the query 0.5 (a
term in both counts once, at 1.0). Snippets are cut around the user's own words only. With an
empty expansion the results equal `POST /api/search` for the same query.

Errors are sent as `{"stage": "error", "message": string}` and leave the connection open:

| Message | When |
|---|---|
| `request already in progress` | a message arrives before the current request's `done`/`error`; the message is ignored and the current request continues |
| `message must be {"query": string}` | the message is not JSON or `query` is not a string |
| `index has not been built yet` | no index exists (Ollama is not called) |
| `index build in progress` | a build is running, so there is no index (Ollama is not called) |
| `server busy, try again later` | 24 requests (8 running, 16 queued) are already in flight across all connections |
| `keyword search failed` | the search itself failed (not expected in normal operation) |

### Errors

Every HTTP error has status `400`, `409` or `500` and the body:

| Field | Type | Description |
|---|---|---|
| `status` | string | `"error"` |
| `message` | string | human-readable reason |

```json
{"status": "error", "message": "index has not been built yet"}
```

## Indexing

- Tokens are maximal runs of letters and digits, lower-cased.
- A light suffix stemmer (not Porter) tries, in order, `ies -> y`, `-es`, `-ing`, `-ed`, `-ly`,
  `-s` (not after `s`) and applies the first rule that leaves a stem of at least 3 characters.
  Known inaccuracies are accepted: `movies -> movy`, `running -> runn`.
- Files are split into batches of 64; each batch is one thread pool task that reads its files,
  accumulates term frequencies locally and merges them into the shared index once.
- The index has the smallest power-of-two number of shards that is at least `4 x threads`.

## Query expansion

`com.coursework.search.ollama` asks Ollama (`POST {OLLAMA_URL}/api/generate`, model
`OLLAMA_MODEL`, default `http://ollama:11434` and `llama3.2:3b`) for related keywords with a
fixed few-shot prompt, temperature 0 and a 5 s timeout.

- The answer is split on commas, trimmed, lower-cased; empty entries and entries longer than
  3 words or 40 characters (sentences rather than keywords) are dropped, duplicates removed
  keeping the first occurrence, and at most 8 terms kept.
- A timeout, an unreachable server or an error status gives an empty list instead of an error,
  so search runs on the original query.
- Answers are cached in a bounded LRU of 256 entries keyed by the trimmed, lower-cased query.
  Failures are not cached.
- The `ollama` service keeps the model loaded (`OLLAMA_KEEP_ALIVE=-1`) and `ollama-init` runs the
  expansion prompt once after the pull. A cold load takes much longer than 5 s, and Ollama
  aborts a load whose client has disconnected, so without the warm-up every expansion would
  time out. Warm calls take about 2-3 s on 8 CPU cores.

Example (`llama3.2:3b`, CPU): `space movies` -> `science fiction, alien, galaxy, cosmos,
adventure, blockbuster, extraterrestrial`.

## Ranking

- BM25 with `k1 = 1.2`, `b = 0.75` and `idf = ln((N - df + 0.5) / (df + 0.5) + 1)`, where `N` is
  the number of indexed documents. Repeated query terms count once.
- Scores are accumulated in a dense array indexed by document; a bounded min-heap keeps the
  top 10, ties broken by the lower document position.
- Snippets are cut from the document file only for the returned results: a window of 100
  characters on each side of the match, trimmed to whole words, whitespace collapsed.
