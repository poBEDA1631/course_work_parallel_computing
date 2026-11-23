"""Search test cases: runs fixed queries against a running server and checks each expectation.

Every case states its expected result as a condition that is checked here, then records what the
server actually returned and pass or fail. Checks that need to know whether a document contains a
word read the whole document from the search container (docker compose exec), because a snippet
shows only the first occurrence of one word. Documents are tokenized and stemmed the same way the
server does it (see Tokenizer and Stemmer).
"""

import argparse
import base64
import csv
import json
import os
import re
import socket
import struct
import subprocess
import sys
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
COMPOSE_FILE = os.path.join(os.path.dirname(HERE), "docker-compose.yml")
REQUEST_TIMEOUT_SECONDS = 60
# Expansion waits for Ollama for at most 5 s; the rest is the search itself.
WS_TIMEOUT_SECONDS = 30
FRAGMENT_LENGTH = 40
FRAGMENT_CONTEXT = 10
DOC_ID = re.compile(r"^[A-Za-z0-9]+$")

RARE_TERM = "xylophonist"
TWO_WORDS = ("xylophonist", "jazz")
BASE_QUERY = "jazz piano"
COMMON_WORD = "movie"
MISSING_WORD = "hemidemisemiquaver"


class Unreachable(Exception):
    pass


def tokens(text):
    """Same split as Tokenizer: runs of letters (categories L*) and decimal digits, lower-cased."""
    out = []
    current = []
    for ch in text:
        if ch.isalpha() or unicodedata.category(ch) == "Nd":
            current.append(ch.lower())
        elif current:
            out.append("".join(current))
            current = []
    if current:
        out.append("".join(current))
    return out


def stem(word):
    """Same rules, in the same order, as Stemmer.stem."""
    n = len(word)
    if word.endswith("ies") and n - 3 >= 3:
        return word[:-3] + "y"
    for suffix in ("es", "ing", "ed", "ly"):
        if word.endswith(suffix) and n - len(suffix) >= 3:
            return word[: -len(suffix)]
    if word.endswith("s") and not word.endswith("ss") and n - 1 >= 3:
        return word[:-1]
    return word


def stems(text):
    return {stem(t) for t in tokens(text)}


class Server:
    def __init__(self, base_url, compose_file):
        self.base_url = base_url
        self.compose_file = compose_file
        self.doc_stems = {}

    def post_search(self, body):
        """Returns (status, parsed JSON body) for POST /api/search with the given JSON body."""
        url = self.base_url + "/api/search"
        request = urllib.request.Request(
            url,
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"null")
        except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
            raise Unreachable(f"Could not connect to {url}: {getattr(e, 'reason', e)}") from e

    def search(self, query):
        return self.post_search({"query": query, "mode": "keyword"})

    def document_stems(self, doc_id):
        if doc_id not in self.doc_stems:
            if not DOC_ID.match(doc_id):
                raise RuntimeError(f"unexpected doc_id {doc_id!r}")
            text = subprocess.run(
                ["docker", "compose", "-f", self.compose_file, "exec", "-T", "search",
                 "cat", f"/data/{doc_id}.txt"],
                check=True, capture_output=True,
            ).stdout.decode("utf-8")
            self.doc_stems[doc_id] = stems(text)
        return self.doc_stems[doc_id]

    def contains(self, doc_id, words):
        """Whether the document has every given word, compared as stems like the index does."""
        return all(stem(w) in self.document_stems(doc_id) for w in words)

    def ws_search(self, query):
        """Sends one query to /ws/search and returns the messages up to done or error."""
        url = urllib.parse.urlsplit(self.base_url)
        try:
            sock = socket.create_connection(
                (url.hostname, url.port or 80), timeout=WS_TIMEOUT_SECONDS)
        except OSError as e:
            raise Unreachable(f"Could not connect to {self.base_url}/ws/search: {e}") from e
        with sock:
            key = base64.b64encode(os.urandom(16)).decode("ascii")
            sock.sendall((
                "GET /ws/search HTTP/1.1\r\n"
                f"Host: {url.netloc}\r\n"
                "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
            ).encode("ascii"))
            reader = sock.makefile("rb")
            status = reader.readline()
            if b" 101 " not in status:
                raise RuntimeError(f"WebSocket handshake failed: {status!r}")
            while reader.readline() not in (b"\r\n", b""):
                pass
            send_frame(sock, 0x1, json.dumps({"query": query}).encode("utf-8"))
            messages = []
            while True:
                opcode, payload = read_frame(reader)
                if opcode == 0x8:
                    break
                if opcode != 0x1:
                    continue
                message = json.loads(payload)
                messages.append(message)
                if message.get("stage") in ("done", "error"):
                    break
            send_frame(sock, 0x8, struct.pack("!H", 1000))
            return messages


def send_frame(sock, opcode, payload):
    """A single masked frame, as RFC 6455 requires from a client."""
    header = bytes([0x80 | opcode])
    n = len(payload)
    if n < 126:
        header += bytes([0x80 | n])
    elif n < 1 << 16:
        header += bytes([0x80 | 126]) + struct.pack("!H", n)
    else:
        header += bytes([0x80 | 127]) + struct.pack("!Q", n)
    mask = os.urandom(4)
    sock.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))


def read_frame(reader):
    """Reads one whole message (joining continuation frames); server frames are not masked."""
    data = b""
    first_opcode = None
    while True:
        b0, b1 = read_exact(reader, 2)
        opcode = b0 & 0x0F
        n = b1 & 0x7F
        if n == 126:
            n = struct.unpack("!H", read_exact(reader, 2))[0]
        elif n == 127:
            n = struct.unpack("!Q", read_exact(reader, 8))[0]
        data += read_exact(reader, n)
        if first_opcode is None:
            first_opcode = opcode
        if b0 & 0x80:
            return first_opcode, data


def read_exact(reader, n):
    data = reader.read(n)
    if len(data) != n:
        raise RuntimeError("WebSocket connection closed by the server")
    return data


def top3(results, query):
    """The first three doc_ids, each with a snippet fragment starting near a query word."""
    words = tokens(query)
    parts = []
    for r in results[:3]:
        snippet = r["snippet"]
        found = [i for i in (snippet.lower().find(w) for w in words) if i >= 0]
        start = max(0, min(found) - FRAGMENT_CONTEXT) if found else 0
        fragment = snippet[start:start + FRAGMENT_LENGTH].strip()
        parts.append(f"{r['doc_id']} \"{'...' if start else ''}{fragment}...\"")
    return "; ".join(parts)


def describe(status, body, query):
    """Short actual result: status, count, top 3 with a snippet fragment, server time."""
    if not isinstance(body, dict) or "results" not in body:
        return f"HTTP {status}, body {json.dumps(body)}"
    results = body["results"]
    text = f"HTTP {status}, {len(results)} results, {body['time_ms']} ms"
    return text + (f"; top 3: {top3(results, query)}" if results else "")


def ranking(body):
    return [(r["doc_id"], r["score"]) for r in body["results"]]


def same_ranking_case(server, case, query, reference):
    """The query must give exactly the top-10 (ids, order, scores) of the reference query."""
    status, body = server.search(query)
    ref_status, ref_body = server.search(reference)
    ok = (status == ref_status == 200 and len(body["results"]) == 10
          and ranking(body) == ranking(ref_body))
    actual = (f"HTTP {status}, {len(body['results'])} results, {body['time_ms']} ms; "
              f"{'identical' if ranking(body) == ranking(ref_body) else 'different'} ids, "
              f"order and scores; top 3: {top3(body['results'], query)}")
    request = json.dumps({"query": query, "mode": "keyword"})
    return (case, query, f"POST /api/search {request}",
            f"200 and the same 10 doc_ids, in the same order with the same scores, "
            f"as \"{reference}\"", actual, ok)


def case_rare_term(server):
    status, body = server.search(RARE_TERM)
    results = body["results"]
    ok = status == 200 and bool(results) and server.contains(results[0]["doc_id"], [RARE_TERM])
    actual = describe(status, body, RARE_TERM)
    if results:
        has = server.contains(results[0]["doc_id"], [RARE_TERM])
        actual += f"; top-1 {'contains' if has else 'does not contain'} the term"
    return ("rare_term", RARE_TERM,
            f"POST /api/search {json.dumps({'query': RARE_TERM, 'mode': 'keyword'})}",
            "200, at least one result, the top-1 document contains the term", actual, ok)


def case_two_words(server):
    query = " ".join(TWO_WORDS)
    status, body = server.search(query)
    results = body["results"]
    both = [server.contains(r["doc_id"], TWO_WORDS) for r in results]
    one = [not b and any(server.contains(r["doc_id"], [w]) for w in TWO_WORDS)
           for r, b in zip(results, both)]
    both_ranks = [i + 1 for i, b in enumerate(both) if b]
    one_ranks = [i + 1 for i, o in enumerate(one) if o]
    ok = (status == 200 and bool(both_ranks) and bool(one_ranks)
          and all(b or o for b, o in zip(both, one))
          and max(both_ranks) < min(one_ranks))
    actual = (f"{describe(status, body, query)}; both words at ranks {both_ranks or '-'}, "
              f"one word only at ranks {one_ranks or '-'}")
    return ("two_words", query,
            f"POST /api/search {json.dumps({'query': query, 'mode': 'keyword'})}",
            "200; the top 10 has documents with both words and documents with only one "
            "(checked on the full document text); every document with both ranks above every "
            "document with one", actual, ok)


def case_missing_word(server):
    status, body = server.search(MISSING_WORD)
    ok = status == 200 and body["results"] == []
    return ("missing_word", MISSING_WORD,
            f"POST /api/search {json.dumps({'query': MISSING_WORD, 'mode': 'keyword'})}",
            "200 and \"results\": []", describe(status, body, MISSING_WORD), ok)


def case_empty_query(server):
    status, body = server.search("")
    ok = status == 200 and body["results"] == []
    return ("empty_query", "", 'POST /api/search {"query": "", "mode": "keyword"}',
            "200 and \"results\": [] (a string query with no indexed term; README: "
            "\"empty if no query term is indexed\")", describe(status, body, ""), ok)


def case_bad_mode(server):
    request = {"query": BASE_QUERY, "mode": "semantic"}
    status, body = server.post_search(request)
    ok = (status == 400 and isinstance(body, dict) and body.get("status") == "error"
          and isinstance(body.get("message"), str))
    return ("bad_mode", BASE_QUERY, f"POST /api/search {json.dumps(request)}",
            "400 and {\"status\": \"error\", \"message\": string}",
            f"HTTP {status}, body {json.dumps(body)}", ok)


def case_normalization(server):
    status, body = server.search(BASE_QUERY)
    scores = [r["score"] for r in body["results"]]
    ok = (status == 200 and len(scores) > 1 and scores[0] == 1.0 and scores[-1] == 0.0
          and all(a >= b for a, b in zip(scores, scores[1:])))
    shown = ", ".join(f"{s:.3f}" for s in scores)
    return ("normalization", BASE_QUERY,
            f"POST /api/search {json.dumps({'query': BASE_QUERY, 'mode': 'keyword'})}",
            "200; top-1 score 1.0, last 0.0, scores never increase down the list",
            f"HTTP {status}, {len(scores)} results, {body['time_ms']} ms; scores {shown}", ok)


def case_common_word(server):
    status, body = server.search(COMMON_WORD)
    ok = status == 200 and len(body["results"]) == 10
    return ("common_word", COMMON_WORD,
            f"POST /api/search {json.dumps({'query': COMMON_WORD, 'mode': 'keyword'})}",
            "200 and exactly 10 results", describe(status, body, COMMON_WORD), ok)


def case_websocket(server):
    messages = server.ws_search(BASE_QUERY)
    stages = [m.get("stage") for m in messages]
    actual = f"stages {' -> '.join(stages)}"
    ok = stages == ["query_expansion", "keyword_search", "done"]
    if ok:
        expansion = messages[0]["data"]
        data = messages[1]["data"]
        results = data["results"]
        expanded = expansion["expanded_terms"]
        words = BASE_QUERY.split()
        allowed = set(words) | {t for term in expanded for t in tokens(term)}
        with_all = [i + 1 for i, r in enumerate(results) if server.contains(r["doc_id"], words)]
        matching = all(stems(" ".join(allowed)) & server.document_stems(r["doc_id"])
                       for r in results)
        ok = (expansion["original"] == BASE_QUERY and bool(results) and bool(with_all)
              and matching)
        actual += (f"; expanded_terms {json.dumps(expanded)}; {len(results)} results, "
                   f"{data['time_ms']} ms; all query words at ranks {with_all or '-'}; "
                   f"{'every' if matching else 'not every'} result has a query or expansion "
                   f"term; top 3: {top3(results, BASE_QUERY)}")
    return ("websocket", BASE_QUERY, f"/ws/search {json.dumps({'query': BASE_QUERY})}",
            "messages query_expansion -> keyword_search -> done; data.original equals the "
            "query; at least one result contains all query words and every result contains "
            "a query or expansion term", actual, ok)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--url", default="http://localhost:8080", help="server base URL")
    parser.add_argument("--compose-file", default=COMPOSE_FILE,
                        help="compose file of the running stack, to read documents")
    parser.add_argument("--out", default=os.path.join(HERE, "search_cases.csv"))
    args = parser.parse_args()
    server = Server(args.url.rstrip("/"), args.compose_file)

    cases = [
        lambda: case_rare_term(server),
        lambda: case_two_words(server),
        lambda: same_ranking_case(server, "case_insensitive", "JAZZ Piano", BASE_QUERY),
        lambda: same_ranking_case(server, "stemming", "jazz albums", "jazz album"),
        lambda: same_ranking_case(server, "repeated_term", "jazz jazz piano", BASE_QUERY),
        lambda: same_ranking_case(server, "punctuation", "jazz, piano!", BASE_QUERY),
        lambda: case_missing_word(server),
        lambda: case_empty_query(server),
        lambda: case_bad_mode(server),
        lambda: case_normalization(server),
        lambda: case_common_word(server),
        lambda: case_websocket(server),
    ]
    failed = 0
    with open(args.out, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["case", "query", "request", "expected", "actual", "verdict"])
        for run in cases:
            case, query, request, expected, actual, ok = run()
            verdict = "pass" if ok else "fail"
            failed += not ok
            writer.writerow([case, query, request, expected, actual, verdict])
            f.flush()
            print(f"{case}: {verdict}: {actual}", flush=True)
    print(f"wrote {args.out}; {len(cases) - failed} passed, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Unreachable as e:
        print(e, file=sys.stderr)
        sys.exit(1)
