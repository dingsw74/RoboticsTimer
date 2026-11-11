"""Standard-library client for GrizzlyTime's local attendance test API."""

import argparse
import json
import os
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


class AttendanceApiError(Exception):
    """An HTTP error returned by the attendance API."""

    def __init__(self, status, code, message):
        super().__init__(message)
        self.status = status
        self.code = code


class AttendanceApiClient:
    def __init__(self, base_url=None, timeout=10):
        self.base_url = (base_url or os.environ.get(
            "GRIZZLYTIME_API_URL", "http://127.0.0.1:8765"
        )).rstrip("/")
        self.timeout = timeout

    def request_json(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = Request(
            self.base_url + path,
            data=data,
            method=method,
            headers={"Content-Type": "application/json"},
        )
        try:
            with urlopen(request, timeout=self.timeout) as response:
                return json.loads(response.read().decode("utf-8"))
        except HTTPError as error:
            response_body = error.read().decode("utf-8")
            try:
                payload = json.loads(response_body)
                details = payload.get("error", {})
            except (json.JSONDecodeError, AttributeError):
                details = {}
            raise AttendanceApiError(
                error.code,
                details.get("code", "HTTP_ERROR"),
                details.get("message", "Attendance API request failed."),
            ) from error
        except URLError as error:
            raise RuntimeError(
                f"Attendance API is unavailable at {self.base_url}: {error.reason}"
            ) from error

    def health(self):
        return self.request_json("GET", "/health")

    def status(self, user_id):
        return self.request_json(
            "GET", f"/api/v1/users/{quote(user_id, safe='')}/status"
        )

    def login(self, user_id, occurred_at=None):
        return self._attendance_action("/api/v1/attendance/login", user_id, occurred_at)

    def logout(self, user_id, occurred_at=None):
        return self._attendance_action("/api/v1/attendance/logout", user_id, occurred_at)

    def _attendance_action(self, path, user_id, occurred_at):
        payload = {"userId": user_id}
        if occurred_at is not None:
            payload["occurredAt"] = occurred_at
        return self.request_json("POST", path, payload)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--url",
        default=os.environ.get("GRIZZLYTIME_API_URL", "http://127.0.0.1:8765"),
        help="API base URL (default: GRIZZLYTIME_API_URL or localhost:8765)",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("health", help="check the API health")
    status_parser = commands.add_parser("status", help="read a user's current status")
    status_parser.add_argument("user_id")
    for action in ("login", "logout"):
        action_parser = commands.add_parser(action, help=f"record a user {action}")
        action_parser.add_argument("user_id")
        action_parser.add_argument(
            "--occurred-at", help="optional ISO-8601 timestamp with timezone offset"
        )

    args = parser.parse_args(argv)
    client = AttendanceApiClient(args.url)
    try:
        if args.command == "health":
            result = client.health()
        elif args.command == "status":
            result = client.status(args.user_id)
        elif args.command == "login":
            result = client.login(args.user_id, args.occurred_at)
        else:
            result = client.logout(args.user_id, args.occurred_at)
        print(json.dumps(result, indent=2))
        return 0
    except AttendanceApiError as error:
        print(
            json.dumps(
                {
                    "status": error.status,
                    "error": {"code": error.code, "message": str(error)},
                },
                indent=2,
            ),
            file=sys.stderr,
        )
        return 1
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
