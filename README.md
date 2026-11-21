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
| `dataset-init` | one-shot dataset preparation into the `dataset` volume, then exits |
| `search` | backend and static Web UI, starts after `dataset-init` completes |

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
