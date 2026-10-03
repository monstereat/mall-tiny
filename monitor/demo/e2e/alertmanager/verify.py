#!/usr/bin/env python3
"""Exercise Prometheus rule evaluation through Alertmanager to the E2E webhook."""

import json
import time
from urllib.error import URLError
from urllib.request import Request, urlopen


BASE_URL = "http://127.0.0.1:8080"
ALERT_NAME = "E2EAlertmanagerNotification"


def request(path, method="GET"):
    req = Request(BASE_URL + path, method=method)
    with urlopen(req, timeout=5) as response:
        return json.loads(response.read())


def wait_for_notification(status, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            notifications = request(f"/notifications?status={status}")
        except (TimeoutError, URLError, json.JSONDecodeError):
            notifications = []
        if any(
            notification.get("status") == status
            and any(alert.get("labels", {}).get("alertname") == ALERT_NAME
                    and alert.get("status") == status
                    for alert in notification.get("alerts", []))
            for notification in notifications
        ):
            return len(notifications)
        time.sleep(1)
    raise RuntimeError(f"Alertmanager webhook did not receive {status} notification")


def main():
    request("/control/reset", method="POST")
    request("/control/firing", method="POST")
    firing_count = wait_for_notification("firing")
    request("/control/resolved", method="POST")
    resolved_count = wait_for_notification("resolved")
    print(json.dumps({
        "alert": ALERT_NAME,
        "firingNotifications": firing_count,
        "resolvedNotifications": resolved_count,
        "path": "Prometheus -> Alertmanager -> local HTTP receiver",
    }))
    request("/control/reset", method="POST")


if __name__ == "__main__":
    main()
