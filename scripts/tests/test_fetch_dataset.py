"""Tests for the resumable download in fetch_dataset.py, against a local server that drops
connections on purpose. Standard library only:

    python3 -m unittest discover -s scripts/tests -v
"""

import gzip
import http.client
import http.server
import importlib.util
import json
import os
import threading
import unittest

_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "fetch_dataset.py")
_SPEC = importlib.util.spec_from_file_location("fetch_dataset", _PATH)
fetch_dataset = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(fetch_dataset)

LINES = [json.dumps({"parent_asin": f"B{i:08d}", "text": "x" * (i % 300)}) for i in range(60000)]
BODY = gzip.compress(("\n".join(LINES) + "\n").encode("utf-8"))


class _Handler(http.server.BaseHTTPRequestHandler):
    """Serves BODY with Range support. Each entry of cut_after is used by one request: that
    request sends only so many bytes and then drops the connection mid-body."""

    cut_after = []
    honour_range = True
    ranges = []

    def do_GET(self):
        requested = self.headers.get("Range")
        _Handler.ranges.append(requested)
        start = 0
        if requested and _Handler.honour_range:
            start = int(requested.split("=")[1].rstrip("-"))
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{len(BODY) - 1}/{len(BODY)}")
        else:
            self.send_response(200)
        self.send_header("Content-Length", str(len(BODY) - start))
        self.end_headers()
        payload = BODY[start:]
        if _Handler.cut_after:
            self.wfile.write(payload[: _Handler.cut_after.pop(0)])
            self.wfile.flush()
            self.connection.shutdown(2)
            return
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


class ResumableDownloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.server.server_address[1]}/reviews.jsonl.gz"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        _Handler.cut_after = []
        _Handler.honour_range = True
        _Handler.ranges = []
        self.logged = []
        self._saved = (fetch_dataset.RESUME_PAUSE_SECONDS, fetch_dataset.log)
        fetch_dataset.RESUME_PAUSE_SECONDS = 0
        fetch_dataset.log = self.logged.append

    def tearDown(self):
        fetch_dataset.RESUME_PAUSE_SECONDS, fetch_dataset.log = self._saved

    def read_all(self):
        download, lines = fetch_dataset.stream_jsonl(self.url)
        try:
            return [line.rstrip("\n") for line in lines]
        finally:
            download.close()

    def test_several_drops_resume_to_identical_content(self):
        _Handler.cut_after = [70000, 60000, 5]

        self.assertEqual(LINES, self.read_all())
        self.assertEqual(
            [None, "bytes=70000-", "bytes=130000-", "bytes=130005-"], _Handler.ranges)
        self.assertEqual(3, len(self.logged))
        self.assertIn("connection lost at byte 70000", self.logged[0])

    def test_download_without_drops_makes_one_request(self):
        self.assertEqual(LINES, self.read_all())
        self.assertEqual([None], _Handler.ranges)
        self.assertEqual([], self.logged)

    def test_ignored_range_fails_at_once(self):
        _Handler.cut_after = [100000]
        _Handler.honour_range = False

        with self.assertRaises(fetch_dataset.RangeNotSupported):
            self.read_all()
        # The first request, then one resume attempt answered 200: no further retries.
        self.assertEqual([None, "bytes=100000-"], _Handler.ranges)

    def test_too_many_consecutive_drops_give_up(self):
        limit = fetch_dataset.MAX_RESUMES
        _Handler.cut_after = [1000] + [0] * (limit + 1)

        with self.assertRaises((OSError, http.client.HTTPException)):
            self.read_all()
        self.assertEqual(1 + limit, len(_Handler.ranges))
        self.assertEqual(limit, len(self.logged))
        self.assertIn(f"({limit}/{limit})", self.logged[-1])


if __name__ == "__main__":
    unittest.main()
