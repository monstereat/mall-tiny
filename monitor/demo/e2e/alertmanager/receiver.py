#!/usr/bin/env python3
"""In-memory HTTP webhook and Prometheus metric fixture used only by E2E."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from threading import Lock
from urllib.parse import parse_qs, urlparse


lock = Lock()
trigger = 0
notifications = []


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, status, value):
        body = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        global trigger
        parsed = urlparse(self.path)
        if parsed.path == "/healthz":
            self._send_json(200, {"status": "ok"})
            return
        if parsed.path == "/metrics":
            with lock:
                value = trigger
            body = (
                "# HELP monitor_e2e_alert_trigger E2E-only Alertmanager trigger.\n"
                "# TYPE monitor_e2e_alert_trigger gauge\n"
                f"monitor_e2e_alert_trigger {value}\n"
            ).encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if parsed.path == "/notifications":
            status = parse_qs(parsed.query).get("status", [None])[0]
            with lock:
                result = [
                    item for item in notifications
                    if status is None or item.get("status") == status
                ]
            self._send_json(200, result)
            return
        self._send_json(404, {"error": "not found"})

    def do_POST(self):
        global trigger
        parsed = urlparse(self.path)
        if parsed.path == "/webhook":
            try:
                payload = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
            except (ValueError, json.JSONDecodeError):
                self._send_json(400, {"error": "invalid JSON"})
                return
            if payload.get("status") not in ("firing", "resolved"):
                self._send_json(400, {"error": "missing firing/resolved status"})
                return
            with lock:
                notifications.append(payload)
            self._send_json(200, {"status": "accepted"})
            return
        if parsed.path in ("/control/firing", "/control/resolved"):
            with lock:
                trigger = 1 if parsed.path.endswith("/firing") else 0
            self._send_json(200, {"trigger": trigger})
            return
        if parsed.path == "/control/reset":
            with lock:
                trigger = 0
                notifications.clear()
            self._send_json(200, {"trigger": trigger, "notifications": 0})
            return
        self._send_json(404, {"error": "not found"})

    def log_message(self, _format, *_args):
        return


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
