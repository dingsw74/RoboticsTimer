import json
import unittest
from unittest.mock import patch

from python.client import AttendanceApiClient, AttendanceApiError


class FakeResponse:
    def __init__(self, body):
        self.body = body

    def __enter__(self):
        return self

    def __exit__(self, exception_type, exception, traceback):
        return False

    def read(self):
        return json.dumps(self.body).encode("utf-8")


class AttendanceApiClientTests(unittest.TestCase):
    @patch("python.client.urlopen")
    def test_login_sends_string_id_and_optional_timestamp(self, urlopen):
        urlopen.return_value = FakeResponse({"state": "logged_in"})
        client = AttendanceApiClient("http://127.0.0.1:8765/")

        result = client.login("001234", "2026-11-10T12:00:00-08:00")

        self.assertEqual({"state": "logged_in"}, result)
        request = urlopen.call_args.args[0]
        self.assertEqual("http://127.0.0.1:8765/api/v1/attendance/login", request.full_url)
        self.assertEqual(
            {"userId": "001234", "occurredAt": "2026-11-10T12:00:00-08:00"},
            json.loads(request.data.decode("utf-8")),
        )

    @patch("python.client.urlopen")
    def test_status_returns_session_and_cumulative_hours(self, urlopen):
        urlopen.return_value = FakeResponse(
            {
                "userId": "001234",
                "state": "logged_out",
                "lastTimeHours": "00:42:15",
                "totalHours": "27:30:45",
            }
        )

        result = AttendanceApiClient().status("001234")

        self.assertEqual("00:42:15", result["lastTimeHours"])
        self.assertEqual("27:30:45", result["totalHours"])

    @patch("python.client.urlopen")
    def test_api_error_preserves_status_and_error_code(self, urlopen):
        from urllib.error import HTTPError
        from io import BytesIO

        urlopen.side_effect = HTTPError(
            "http://127.0.0.1:8765/api/v1/attendance/logout",
            409,
            "Conflict",
            {},
            BytesIO(b'{"error":{"code":"INVALID_TRANSITION","message":"Not active."}}'),
        )

        with self.assertRaises(AttendanceApiError) as raised:
            AttendanceApiClient().logout("001234")

        self.assertEqual(409, raised.exception.status)
        self.assertEqual("INVALID_TRANSITION", raised.exception.code)
        self.assertEqual("Not active.", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
