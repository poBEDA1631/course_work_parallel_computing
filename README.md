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

On the first start `ollama-init` downloads the `llama3.2:3b` model (about 2 GB).
Until it is available, `/api/health` reports `"ollama": false`; the backend does not wait for it.

The host port can be changed with the `PORT` variable, e.g. `PORT=9090 docker compose up -d`.

Services:

| Service | Role |
|---|---|
| `ollama` | Ollama server, models are kept in the `ollama-models` volume |
| `ollama-init` | one-shot `ollama pull llama3.2:3b`, then exits |
| `dataset-init` | one-shot corpus download and preparation into the `dataset` volume, then exits |
| `search` | backend and static Web UI, starts after `dataset-init` completes |

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
- On success `.complete` is written into the directory. If it exists, later runs do nothing;
  if it is missing (an interrupted run), the directory's `.txt` files are deleted and the corpus
  is built again.

The first start downloads both meta files in full (about 0.5 GB compressed) and the beginning of
both review files, and took about 15 minutes in testing; the result is about 611,000 documents,
1.5 GB of text.

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
| `ollama` | boolean | whether the Ollama model is available |

```json
{"status": "ok", "ollama": false}
```

### `POST /api/index`

Builds a new index over every `.txt` file in `DATASET_DIR` and responds when the build is
finished. The previous index, if any, stays in use until the new one is complete.

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

Statistics of the last successful build; during a rebuild it still reports the previous one.

Response `200`:

| Field | Type | Description |
|---|---|---|
| `documents` | integer | documents in the index |
| `index_build_time_ms` | integer | build time in milliseconds |
| `threads_used` | integer | worker threads used for the build |

```json
{"documents": 611120, "index_build_time_ms": 74611, "threads_used": 8}
```

Errors: `409` if no index has been built yet.

### `POST /api/search`

BM25 keyword search over the current index; during a rebuild the previous index answers.

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
`409` if no index has been built yet.

### Errors

Every error has status `400`, `409` or `500` and the body:

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

## Ranking

- BM25 with `k1 = 1.2`, `b = 0.75` and `idf = ln((N - df + 0.5) / (df + 0.5) + 1)`, where `N` is
  the number of indexed documents. Repeated query terms count once.
- Scores are accumulated in a dense array indexed by document; a bounded min-heap keeps the
  top 10, ties broken by the lower document position.
- Snippets are cut from the document file only for the returned results: a window of 100
  characters on each side of the match, trimmed to whole words, whitespace collapsed.
