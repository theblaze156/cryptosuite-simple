import io
import json
import os
import tempfile
import threading
import unittest
from contextlib import redirect_stdout
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, HTTPServer

import main

FAKE_KEY = "bsk_FAKE_test_key_000"
SEEN = []

SUMMARY = {"totals": {"addresses": 3}, "per_chain": {
    "btc": {"count": 1, "balance_available": True, "balance_sat": 150000000},
    "ltc": {"count": 1, "balance_available": False},
    "bch": {"count": 0, "balance_available": False}}}
PORTFOLIO = {"accounts": [{"account": {"address": "0x000000000000000000000000000000000000dEaD"},
    "balances": [{"chain_id": 1, "native_symbol": "ETH", "native_wei": "2500000000000000000",
                  "tokens": [{"symbol": "USDT", "address": "0xt", "decimals": 6, "balance": "12500000"}]},
                 {"chain_id": 137, "native_symbol": "POL", "native_wei": "1000000000000000000", "tokens": []}]}]}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def _send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

    def _rec(self, body=None):
        SEEN.append({"method": self.command, "path": self.path, "headers": dict(self.headers), "body": body})

    def do_GET(self):
        self._rec()
        if self.headers.get("X-API-Key") == "bsk_bad":
            return self._send(403, {"detail": "forbidden"})
        routes = {"/api/health": {"status": "ok"}, "/api/v1/watchlist/summary": SUMMARY,
                  "/api/v1/evm/portfolio": PORTFOLIO}
        self._send(200, routes.get(self.path, {}))

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        self._rec(json.loads(self.rfile.read(n)))
        self._send(200, {"imported": 1, "skipped": 0, "invalid": 0, "rejected": 0})

    def do_DELETE(self):
        self._rec(); self._send(403, {"detail": "missing scope write"})


class T(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = HTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.url = "http://127.0.0.1:%d" % cls.srv.server_port

    @classmethod
    def tearDownClass(cls): cls.srv.shutdown()

    def setUp(self): SEEN.clear()

    def test_headers(self):
        main.Api(self.url, "edge-fake", FAKE_KEY).summary()
        h = SEEN[-1]["headers"]
        self.assertEqual(h["X-API-Key"], FAKE_KEY)
        self.assertEqual(h["X-Crypto-Auth"], "edge-fake")
        main.Api(self.url, "edge-fake", FAKE_KEY).health()
        self.assertNotIn("X-API-Key", SEEN[-1]["headers"])

    def test_403(self):
        with self.assertRaises(main.ApiError) as c:
            main.Api(self.url, "", "bsk_bad").summary()
        self.assertIn("403", str(c.exception))
        with self.assertRaises(main.ApiError) as c:
            main.Api(self.url, "", FAKE_KEY).delete("7")
        self.assertIn("только для чтения", str(c.exception))

    def test_add_body(self):
        main.Api(self.url, "", FAKE_KEY).add("  bc1qexamplefake  ", "auto")
        self.assertEqual(SEEN[-1]["path"], "/api/v1/import/watchlist")
        self.assertEqual(SEEN[-1]["body"], {"text": "bc1qexamplefake", "default_chain": "auto"})

    def test_balance_totals(self):
        agg = main.aggregate(SUMMARY, PORTFOLIO)
        self.assertEqual(agg["BTC"], Decimal("1.5"))
        self.assertIsNone(agg["LTC"])
        self.assertEqual(agg["ETH"], Decimal("2.5"))
        self.assertEqual(agg["MATIC"], Decimal("1"))
        self.assertEqual(agg["USDT"], Decimal("12.5"))
        self.assertEqual(main.fmt(agg["USDC"]), "0")
        self.assertEqual(main.fmt(agg["LTC"]), "нет данных")
        self.assertEqual(main.aggregate(None, None)["USDT"], None)

    def test_cli_no_key_leak(self):
        os.environ.update(CRYPTOSUITE_URL=self.url, CRYPTOSUITE_API_KEY=FAKE_KEY, CRYPTOSUITE_EDGE_KEY="edge-fake")
        main.CONFIG_PATH = os.path.join(tempfile.mkdtemp(), "c.json")
        buf = io.StringIO()
        with redirect_stdout(buf):
            self.assertEqual(main.main(["balance"]), 0)
        out = buf.getvalue()
        self.assertIn("BTC   1.5", out)
        self.assertNotIn(FAKE_KEY, out)

    def test_config_perms(self):
        p = os.path.join(tempfile.mkdtemp(), "x", "config.json")
        main.save_config({"url": "https://example.com", "api_key": FAKE_KEY}, p)
        self.assertEqual(os.stat(p).st_mode & 0o777, 0o600)


if __name__ == "__main__":
    unittest.main()
