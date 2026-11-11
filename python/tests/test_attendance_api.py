"""Opt-in integration tests for a disposable GrizzlyTime test spreadsheet."""

import os
import random
import unittest

from python.client import AttendanceApiClient, AttendanceApiError


RUN_INTEGRATION = (
    os.environ.get("GRIZZLYTIME_RUN_INTEGRATION") == "1"
    and os.environ.get("GRIZZLYTIME_TEST_SHEET_CONFIRMED") == "YES"
    and bool(os.environ.get("GRIZZLYTIME_TEST_USER_ID"))
)


@unittest.skipUnless(
    RUN_INTEGRATION,
    "Set explicit integration-test variables; see python/README.md.",
)
class AttendanceApiIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = AttendanceApiClient()
        cls.user_id = os.environ["GRIZZLYTIME_TEST_USER_ID"]
        if not cls.user_id.isdigit():
            raise ValueError("GRIZZLYTIME_TEST_USER_ID must contain only digits.")
        health = cls.client.health()
        if health.get("status") != "ok":
            raise AssertionError(f"Unexpected health response: {health!r}")

    def test_attendance_flow_and_error_responses(self):
        missing_user = str(random.randrange(10**30, 10**31))
        try:
            self.client.status(missing_user)
            self.fail("An unknown user ID should return 404.")
        except AttendanceApiError as error:
            self.assertEqual(404, error.status)
            self.assertEqual("USER_NOT_FOUND", error.code)

        try:
            self.client.request_json(
                "POST",
                "/api/v1/attendance/login",
                {"userId": self.user_id, "occurredAt": "not-a-timestamp"},
            )
            self.fail("An invalid timestamp should return 400.")
        except AttendanceApiError as error:
            self.assertEqual(400, error.status)
            self.assertEqual("INVALID_REQUEST", error.code)

        initial_status = self.client.status(self.user_id)
        self.assertEqual("logged_out", initial_status["state"])

        login_attempted = False
        try:
            login_attempted = True
            login = self.client.login(self.user_id)
            self.assertEqual("logged_in", login["state"])
            self.assertEqual(self.user_id, login["userId"])

            try:
                self.client.login(self.user_id)
                self.fail("A duplicate login should return 409.")
            except AttendanceApiError as error:
                self.assertEqual(409, error.status)
                self.assertEqual("INVALID_TRANSITION", error.code)

            current_status = self.client.status(self.user_id)
            self.assertEqual("logged_in", current_status["state"])

            logout = self.client.logout(self.user_id)
            self.assertEqual("logged_out", logout["state"])
            self.assertGreaterEqual(logout["durationSeconds"], 0)
            self.assertIn("durationCredited", logout)

            try:
                self.client.logout(self.user_id)
                self.fail("A duplicate logout should return 409.")
            except AttendanceApiError as error:
                self.assertEqual(409, error.status)
                self.assertEqual("INVALID_TRANSITION", error.code)

            final_status = self.client.status(self.user_id)
            self.assertEqual("logged_out", final_status["state"])
        finally:
            if login_attempted and self.client.status(self.user_id)["state"] == "logged_in":
                self.client.logout(self.user_id)


if __name__ == "__main__":
    unittest.main()
