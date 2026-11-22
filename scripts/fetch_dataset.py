"""Builds the search corpus from Amazon Reviews 2023: one <parent_asin>.txt per product.

Reviews are streamed (never downloaded in full) and staged per product until each category's
byte budget is spent; the meta files are then streamed to prepend each staged product's title
and description, writing documents until the budget, which counts the final files, is spent.
A dropped connection is resumed with an HTTP Range request. A .complete marker is written last,
so a run that still fails is redone from scratch instead of leaving a partial corpus behind.
"""

import gzip
import html
import http.client
import io
import json
import os
import re
import shutil
import sys
import time
import urllib.request

BASE_URL = "https://mcauleylab.ucsd.edu/public_datasets/data/amazon_2023/raw"
CATEGORIES = ("Movies_and_TV", "CDs_and_Vinyl")
MARKER = ".complete"
STAGING = ".staging"
FLUSH_BYTES = 64 * 1024 * 1024
PROGRESS_BYTES = 100 * 1024 * 1024
TAG = re.compile(r"<[^>]+>")
SAFE_ASIN = re.compile(r"^[A-Za-z0-9]+$")
READ_TIMEOUT_SECONDS = 120
MAX_RESUMES = 5
RESUME_PAUSE_SECONDS = 10


def log(message):
    print(message, flush=True)


def clean(text):
    return " ".join(html.unescape(TAG.sub(" ", text or "")).split())


class RangeNotSupported(Exception):
    """Resuming is impossible; not an OSError, so it is never retried."""


class ResumableDownload(io.RawIOBase):
    """Bytes of one URL; after a dropped connection, continues from the next byte with Range.

    The gzip decoder reading from it never notices a resume, because the byte stream it sees is
    the same. Up to MAX_RESUMES consecutive failures are retried, with growing pauses.
    """

    def __init__(self, url):
        self.url = url
        self.offset = 0
        self.length = None
        self.response = None
        self._connect()

    def _connect(self):
        headers = {"Range": f"bytes={self.offset}-"} if self.offset else {}
        request = urllib.request.Request(self.url, headers=headers)
        response = urllib.request.urlopen(request, timeout=READ_TIMEOUT_SECONDS)
        if self.offset and response.status != 206:
            response.close()
            raise RangeNotSupported(f"server ignored the Range request (HTTP {response.status})")
        if self.length is None:
            length = response.headers.get("Content-Length")
            self.length = int(length) if length is not None else None
        self.response = response

    def readable(self):
        return True

    def readinto(self, buffer):
        failures = 0
        while True:
            try:
                if self.response is None:
                    self._connect()
                n = self.response.readinto(buffer)
                if n == 0 and self.length is not None and self.offset < self.length:
                    raise http.client.IncompleteRead(b"", self.length - self.offset)
                self.offset += n
                return n
            except (OSError, http.client.HTTPException) as e:
                failures += 1
                if failures > MAX_RESUMES:
                    raise
                pause = RESUME_PAUSE_SECONDS * failures
                log(f"connection lost at byte {self.offset} of {self.url.rsplit('/', 1)[-1]}: "
                    f"{e!r}; resuming in {pause} s ({failures}/{MAX_RESUMES})")
                self._drop()
                time.sleep(pause)

    def _drop(self):
        if self.response is not None:
            try:
                self.response.close()
            except OSError:
                pass
            self.response = None

    def close(self):
        self._drop()
        super().close()


def stream_jsonl(url):
    download = ResumableDownload(url)
    buffered = io.BufferedReader(download, buffer_size=1024 * 1024)
    lines = io.TextIOWrapper(gzip.GzipFile(fileobj=buffered), encoding="utf-8", errors="replace")
    return download, lines


def reset(out_dir):
    for name in os.listdir(out_dir):
        if name.endswith(".txt"):
            os.remove(os.path.join(out_dir, name))
    shutil.rmtree(os.path.join(out_dir, STAGING), ignore_errors=True)


class Stager:
    """Buffers review text per product and appends it to staging files in large flushes."""

    def __init__(self, staging_dir):
        self.staging_dir = staging_dir
        self.buffer = {}
        self.buffered = 0

    def add(self, asin, data):
        self.buffer.setdefault(asin, []).append(data)
        self.buffered += len(data)
        if self.buffered >= FLUSH_BYTES:
            self.flush()

    def flush(self):
        for asin, chunks in self.buffer.items():
            with open(self.path(asin), "ab") as f:
                f.writelines(chunks)
        self.buffer.clear()
        self.buffered = 0

    def path(self, asin):
        return os.path.join(self.staging_dir, asin)


def stage_reviews(category, budget, stager):
    url = f"{BASE_URL}/review_categories/{category}.jsonl.gz"
    log(f"[{category}] streaming reviews from {url}")
    asins = set()
    used = 0
    next_report = PROGRESS_BYTES
    response, lines = stream_jsonl(url)
    try:
        for line in lines:
            review = json.loads(line)
            asin = review.get("parent_asin") or ""
            if not SAFE_ASIN.match(asin):
                continue
            text = clean(review.get("title")) + "\n" + clean(review.get("text")) + "\n\n"
            data = text.encode("utf-8")
            stager.add(asin, data)
            asins.add(asin)
            used += len(data)
            if used >= next_report:
                log(f"[{category}] {used // (1024 * 1024)} MiB of reviews, {len(asins)} products")
                next_report += PROGRESS_BYTES
            if used >= budget:
                break
    finally:
        response.close()
    stager.flush()
    log(f"[{category}] staged {used} bytes of reviews for {len(asins)} products")
    return asins


def write_document(out_dir, staging_path, asin, header):
    target = os.path.join(out_dir, asin + ".txt")
    with open(target, "wb") as out, open(staging_path, "rb") as reviews:
        out.write(header.encode("utf-8"))
        shutil.copyfileobj(reviews, out)
    os.remove(staging_path)
    return os.path.getsize(target)


def write_documents(category, pending, out_dir, stager, budget):
    """Prepends title and description to staged products until the category budget is spent."""
    url = f"{BASE_URL}/meta_categories/meta_{category}.jsonl.gz"
    log(f"[{category}] streaming meta for {len(pending)} products from {url}")
    written = documents = 0
    response, lines = stream_jsonl(url)
    try:
        for line in lines:
            if not pending or written >= budget:
                break
            item = json.loads(line)
            asin = item.get("parent_asin")
            if asin not in pending:
                continue
            pending.discard(asin)
            staging_path = stager.path(asin)
            if not os.path.exists(staging_path):
                continue
            description = " ".join(item.get("description") or [])
            header = clean(item.get("title")) + "\n" + clean(description) + "\n\n"
            written += write_document(out_dir, staging_path, asin, header)
            documents += 1
    finally:
        response.close()

    # What is left either has no meta record (kept with reviews only while the budget allows)
    # or did not fit into the budget (dropped).
    without_meta = dropped = 0
    for asin in pending:
        staging_path = stager.path(asin)
        if not os.path.exists(staging_path):
            continue
        if written < budget:
            written += write_document(out_dir, staging_path, asin, "")
            without_meta += 1
        else:
            os.remove(staging_path)
            dropped += 1
    log(
        f"[{category}] wrote {documents + without_meta} documents "
        f"({without_meta} without meta, {dropped} dropped over budget), {written} bytes"
    )


def main():
    out_dir = os.environ.get("DATASET_DIR", "/data")
    max_bytes = int(os.environ.get("DATASET_MAX_BYTES", "1500000000"))
    os.makedirs(out_dir, exist_ok=True)
    if os.path.exists(os.path.join(out_dir, MARKER)):
        log(f"dataset already complete in {out_dir}, nothing to do")
        return
    reset(out_dir)
    staging_dir = os.path.join(out_dir, STAGING)
    os.makedirs(staging_dir)
    started = time.time()

    stager = Stager(staging_dir)
    budget = max_bytes // len(CATEGORIES)
    staged = {category: stage_reviews(category, budget, stager) for category in CATEGORIES}
    for category in CATEGORIES:
        write_documents(category, staged[category], out_dir, stager, budget)
    shutil.rmtree(staging_dir)

    total = sum(
        os.path.getsize(os.path.join(out_dir, name))
        for name in os.listdir(out_dir)
        if name.endswith(".txt")
    )
    count = sum(1 for name in os.listdir(out_dir) if name.endswith(".txt"))
    with open(os.path.join(out_dir, MARKER), "w") as f:
        f.write(f"{count} documents, {total} bytes\n")
    log(f"done: {count} documents, {total / 1e9:.2f} GB in {time.time() - started:.0f} s")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        log(f"dataset-init failed: {e!r}")
        sys.exit(1)
