import unittest
from unittest.mock import Mock

from python.client import AttendanceApiError
from python.poisson_process import (
    ApiUnresponsiveError,
    Event,
    make_attendance_handler,
    run_poisson,
)


class PoissonAttendanceHandlerTests(unittest.TestCase):
    def setUp(self):
        self.event = Event(index=0, time=1.5, gap=1.5)
        self.client = Mock()

    def test_logged_out_user_is_logged_in(self):
        self.client.status.return_value = {"state": "logged_out"}
        self.client.login.return_value = {"state": "logged_in"}
        handler = make_attendance_handler(self.client, ["123456"], seed=1)

        handler(self.event)

        self.client.status.assert_called_once_with("123456")
        self.client.login.assert_called_once_with("123456")
        self.client.logout.assert_not_called()

    def test_logged_in_user_is_logged_out(self):
        self.client.status.return_value = {"state": "logged_in"}
        self.client.logout.return_value = {"state": "logged_out"}
        handler = make_attendance_handler(self.client, ["1177730"], seed=1)

        handler(self.event)

        self.client.status.assert_called_once_with("1177730")
        self.client.login.assert_not_called()
        self.client.logout.assert_called_once_with("1177730")

    def test_unknown_user_is_skipped_without_attendance_action(self):
        self.client.status.side_effect = AttendanceApiError(
            404, "USER_NOT_FOUND", "User was not found."
        )
        handler = make_attendance_handler(self.client, ["123456"], seed=1)

        handler(self.event)

        self.client.login.assert_not_called()
        self.client.logout.assert_not_called()

    def test_status_backend_failure_stops_without_attendance_action(self):
        self.client.status.side_effect = AttendanceApiError(
            503, "ATTENDANCE_BACKEND_UNAVAILABLE", "Attendance backend unavailable."
        )
        handler = make_attendance_handler(self.client, ["123456"], seed=1)

        with self.assertRaises(ApiUnresponsiveError):
            run_poisson(100, on_event=handler, max_events=3, seed=1)

        self.client.status.assert_called_once_with("123456")
        self.client.login.assert_not_called()
        self.client.logout.assert_not_called()

    def test_status_without_api_response_stops_event_loop(self):
        self.client.status.side_effect = RuntimeError("request timed out")
        handler = make_attendance_handler(self.client, ["123456"], seed=1)

        with self.assertRaises(ApiUnresponsiveError):
            run_poisson(100, on_event=handler, max_events=3, seed=1)

        self.client.status.assert_called_once_with("123456")
        self.client.login.assert_not_called()
        self.client.logout.assert_not_called()

    def test_logout_without_api_response_stops_event_loop(self):
        self.client.status.return_value = {"state": "logged_in"}
        self.client.logout.side_effect = RuntimeError("connection refused")
        handler = make_attendance_handler(self.client, ["123456"], seed=1)

        with self.assertRaises(ApiUnresponsiveError):
            handler(self.event)

    def test_logout_backend_failure_stops_event_loop(self):
        self.client.status.return_value = {"state": "logged_in"}
        self.client.logout.side_effect = AttendanceApiError(
            503, "ATTENDANCE_BACKEND_UNAVAILABLE", "Attendance backend unavailable."
        )
        handler = make_attendance_handler(self.client, ["123456"], seed=1)

        with self.assertRaises(ApiUnresponsiveError):
            handler(self.event)

    def test_invalid_status_stops_without_attendance_action(self):
        self.client.status.return_value = {"state": "unknown"}
        handler = make_attendance_handler(self.client, ["123456"], seed=1)

        with self.assertRaises(ApiUnresponsiveError):
            handler(self.event)

        self.client.login.assert_not_called()
        self.client.logout.assert_not_called()

    def test_rejects_empty_or_invalid_user_pool(self):
        with self.assertRaises(ValueError):
            make_attendance_handler(self.client, [])
        with self.assertRaises(ValueError):
            make_attendance_handler(self.client, ["not-a-numeric-id"])


if __name__ == "__main__":
    unittest.main()
