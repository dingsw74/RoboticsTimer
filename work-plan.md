# Work plan for improving the classroom attendance login/logout flow

## Goal
Improve the reliability of the current single-device classroom attendance system without changing the overall user experience or the Google Sheets workflow.

## Current constraints
- Single application instance used throughout the school day
- One device / one terminal
- Around 50 users
- Users do not log in at the same time
- App runs from roughly 12:00 PM to 9:00 PM

## Design assumptions
This system is not a general-purpose auth system. It is a classroom attendance tracker. The app can reasonably use the sheet as the state store, but the logic should be hardened to avoid stale or inconsistent state.

## Recommended improvements

### 1. Replace single boolean state with event-based session tracking
Current issue:
- The app uses a single column like "logged in" and stores a simple TRUE/FALSE value.
- This is easy to corrupt or leave stale after a crash.

Recommendation:
- Keep a dedicated attendance log sheet (or section) that records each event as an append-only row.
- Each event should include:
  - user_id
  - event_type (login/logout)
  - timestamp_utc
  - source_device
  - session_id (optional)
  - notes
- Compute the current login state from the latest event for that user, rather than trusting a mutable boolean cell.

Why this helps:
- easier to audit
- easier to recover from stale state
- supports troubleshooting when a user appears logged in unexpectedly

### 2. Add stale-session cleanup on startup
Current issue:
- If the app crashes or loses power while a user is logged in, the sheet may remain in an invalid state.

Recommendation:
- On app startup, scan the attendance log for active sessions that do not have a matching closure.
- Mark them as stale or timed out if they exceed a safe inactivity threshold.
- Optionally display a warning and allow reconciliation.

Why this helps:
- prevents a user from being permanently stuck logged in after a crash
- improves operational resilience

### 3. Make logout idempotent
Current issue:
- Logout logic may run twice or after a network interruption, producing duplicate or incorrect duration counts.

Recommendation:
- Before writing logout data, check whether the user already has an active session.
- If the user is already logged out, ignore the action.
- If the user is active, close the active session and calculate elapsed time once.

Why this helps:
- avoids double-counting time
- reduces accidental duplicate updates

### 4. Use time-safe calculations with UTC timestamps
Current issue:
- The app subtracts hour/minute/second fields manually and validates same-day sessions.
- This breaks around midnight and can mis-handle timezone transitions.

Recommendation:
- Store all login/logout timestamps in ISO-8601 UTC strings.
- Compute elapsed duration as a proper time difference using a real datetime library.
- If local display is needed, convert to local time only when presenting the result.

Why this helps:
- avoids manual arithmetic bugs
- reduces cross-day errors
- supports day rollover cleanly

### 5. Keep the sheet as a reporting layer, not the process state
Current issue:
- The app uses raw Google Sheet cells as active session state.

Recommendation:
- Treat the sheet as the summary/reporting surface.
- Keep live session logic in memory or in a local session model while the app is running.
- Periodically synchronize the live state back to the sheet.

Why this helps:
- reduces direct dependence on cell values
- makes the app easier to reason about
- reduces accidental corruption from manual edits

### 6. Add a lightweight “status validation” guard before login/logout actions
Current issue:
- The app trusts the sheet value without checking surrounding state.

Recommendation:
- Before login, validate that the user is not already in an active session.
- Before logout, validate that the user is currently active and the last event matches the expected state.
- Log warnings when an invalid transition is detected.

Why this helps:
- catches stale states early
- improves troubleshooting with a clear audit trail

### 7. Add a small admin reset option for the session day
Current issue:
- If a user is stuck in the wrong state, there is no simple recovery path.

Recommendation:
- Add an admin-only reset action that clears active sessions for the current day or resets a specific user.
- Require confirmation before writing the reset.

Why this helps:
- reduces operational disruption
- keeps the app usable when something goes wrong

## Minimal implementation priority
The most valuable improvements, in order, are:
1. event-based login/logout tracking
2. idempotent logout
3. stale-session cleanup on startup
4. UTC time calculations
5. validation checks before state transitions

## Scope for a targeted change
This work should stay within the current app architecture:
- keep the same Google Sheets attendance sheet structure
- keep the same UI flow for scanning IDs
- avoid introducing a full database or multi-user web backend
- focus on making the existing single-device design more reliable

## Expected outcome
After these changes, the system should still behave the same for a teacher or staff member in a classroom, but it should be noticeably more resilient to:
- crashes
- stale user state
- duplicate logout attempts
- cross-day time errors
- manual sheet tampering

## Suggested next implementation step
Start by adding a session event log and a small helper that resolves the current state of a user from the latest event in that log, then replace the direct TRUE/FALSE check in the login/logout flow.

## Testability plan: Python client to headless Java API

### Goal and scope
Allow a Python test runner to exercise the Java attendance business logic and the real Google Sheets driver without launching JavaFX. The Python program must call Java; it must not write attendance cells directly, or it would bypass the logic under test.

This is a test interface, not a production service. Keep it disabled in normal app launches and require a dedicated test spreadsheet. The first implementation should preserve the current user-facing flow; larger behavior changes (such as event-based sessions) should be separate work, tested after the boundary is in place.

### Proposed component layout

- `services/AttendanceService.java`
  - Owns the login/logout/status use cases.
  - Contains no JavaFX imports or UI notifications.
  - Receives a repository and a `Clock` so logic can be tested independently and time can be controlled.
- `databases/AttendanceRepository.java`
  - Small interface for the operations needed by the service: find a user, get their current state, record login/logout, and read status.
- `databases/GoogleSheetsAttendanceRepository.java`
  - Implements the interface using the existing `DatabaseUtils` / `DatabaseProcess` path.
  - Keeps the existing spreadsheet layout as the persistence format for this first testing milestone.
- `activities/UserActivity.java` and UI callers
  - Delegate the existing login/logout use cases to `AttendanceService`.
  - Keep presentation work (JavaFX messages, confirmation dialogs, registration prompts) in the UI/activity layer.
  - Keep interactive registration out of the headless endpoint: an unknown ID should return a clear `USER_NOT_FOUND` response rather than trying to show a dialog.
- `api/AttendanceApiMain.java` and `api/AttendanceHttpServer.java`
  - Standalone Java entry point and thin HTTP/JSON adapter. Do not start `GrizzlyTime` or JavaFX.
  - Use the JDK's local HTTP server if supported by the configured JDK; otherwise choose a small HTTP library only after confirming the supported Java versions.
  - Parse/validate HTTP input, call `AttendanceService`, and translate results to HTTP responses. The API must not contain attendance rules or write to Sheets itself.
- `python/client.py`
  - Minimal standard-library HTTP client for the API.
- `python/tests/test_attendance_api.py`
  - Sequential integration scenarios against a dedicated test spreadsheet.

Add Java unit tests for `AttendanceService` with an in-memory/fake repository. These give fast coverage without network access; Python integration tests then verify the HTTP-to-service-to-Google-Sheets path.

### Launch and test-data safety

- Keep the existing `Main` / JavaFX launch path unchanged.
- Add a separate Gradle `JavaExec` task (for example `runAttendanceApi`) whose main class is `api.AttendanceApiMain`.
- Require an explicit test-mode flag and an explicit test config path to start the API. Fail startup if either is missing or if the configured spreadsheet is not designated for testing; never silently fall back to `config.json`.
- Bind only to `127.0.0.1`, use a configurable port with a safe default, and do not enable permissive CORS.
- Use a dedicated test spreadsheet with the same tab/column schema. Seed test users there; never point the test runner at the classroom production sheet.
- Do not commit credentials, OAuth tokens, spreadsheet IDs, or a populated test config. Document local setup with a template containing placeholders.
- Verify how Google OAuth authorization works without JavaFX `HostServices`. If the existing token is unavailable, the API should fail with actionable setup instructions rather than hang or use a production credential implicitly.

### HTTP contract (version 1)

All responses use JSON and UTF-8. `userId` is a string to preserve leading zeroes. Timestamps are ISO-8601 with an explicit UTC offset; if omitted, Java uses its injected system clock.

| Method and path | Request | Success response |
|---|---|---|
| `GET /health` | none | `200 {"status":"ok"}` after service and Sheets initialization succeeds |
| `GET /api/v1/users/{userId}/status` | none | `200 {"userId":"001234","state":"logged_in"}` |
| `POST /api/v1/attendance/login` | `{"userId":"001234","occurredAt":"2026-11-10T20:00:00Z"}` (`occurredAt` optional) | `200 {"userId":"001234","state":"logged_in","occurredAt":"..."}` |
| `POST /api/v1/attendance/logout` | `{"userId":"001234","occurredAt":"2026-11-10T21:00:00Z"}` (`occurredAt` optional) | `200 {"userId":"001234","state":"logged_out","occurredAt":"...","durationSeconds":3600,"durationCredited":true}` |

`occurredAt` is available only in the explicitly enabled local test API, so Python can cover time boundaries deterministically. Validate it strictly and reject malformed values; the API must not permit arbitrary timestamps in the normal desktop launch.

Error responses have a stable shape, for example `{"error":{"code":"USER_NOT_FOUND","message":"..."}}`. Use `400` for invalid JSON/fields/timestamps, `404` for an unknown user, `409` for an invalid transition (such as logging out a user who is not active), and `503` for a Sheets/OAuth dependency failure. Do not return success-shaped defaults when a Sheets operation fails.

The API binds specifically to `127.0.0.1`, handles requests sequentially, and limits JSON bodies to 4 KiB. It has no authentication; keep it stopped except during local testing, and use only a disposable test sheet.

Do not include a general reset endpoint in the first version: reset is destructive and can be done by reseeding the disposable test spreadsheet. If reset automation becomes necessary, define a test-only, explicitly scoped reset operation separately.

### Example Python client

Use the standard library initially so the test folder has no extra runtime dependency:

```python
import json
import os
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

BASE_URL = os.environ.get("GRIZZLYTIME_API_URL", "http://127.0.0.1:8765")


def request_json(method, path, body=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = Request(
        BASE_URL + path,
        data=data,
        method=method,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8"))
    except URLError as error:
        raise RuntimeError(f"Attendance API is unavailable: {error}") from error


def login(user_id, occurred_at=None):
    body = {"userId": user_id}
    if occurred_at is not None:
        body["occurredAt"] = occurred_at
    return request_json("POST", "/api/v1/attendance/login", body)


def logout(user_id, occurred_at=None):
    body = {"userId": user_id}
    if occurred_at is not None:
        body["occurredAt"] = occurred_at
    return request_json("POST", "/api/v1/attendance/logout", body)
```

An integration test should use a pre-seeded user ID from the test spreadsheet, check initial status, log in, check status, log out, and verify both the returned state and the resulting sheet values. Use a disposable user/spreadsheet or restore the fixture after each scenario; never run destructive or time-controlled scenarios against production.

### Implementation phases and acceptance checks

1. **Extract the service boundary — complete**
   - Add the repository interface and `AttendanceService`; move decision-making out of UI callbacks.
   - Keep JavaFX dialogs, messages, and new-user registration outside the service.
   - Acceptance: existing UI login/logout still follows the same valid-user flow; Java unit tests cover logged-in/logged-out transitions and failure cases using a fake repository.
2. **Implement the Google Sheets adapter — code complete; live check pending**
   - Wrap existing `DatabaseUtils` operations behind the repository; do not make Python call Google Sheets directly.
   - Add an explicit test config path and confirm headless OAuth startup behavior.
   - Acceptance: a manual test against the dedicated sheet can read status and write a login/logout without touching the production spreadsheet.
3. **Add the opt-in HTTP adapter — implementation complete**
   - Add the separate Java entry point and Gradle task; validate payloads and return stable error responses.
   - Acceptance: API is unreachable unless explicitly started, listens only on loopback, health fails clearly when Sheets is unavailable, and the normal desktop launch is unchanged.
4. **Add the Python client and integration tests — implementation complete; live run pending**
   - Add `python/client.py`, `python/tests/test_attendance_api.py`, and brief run instructions.
   - Acceptance: the test verifies login → status → logout against the real test spreadsheet; malformed input, unknown IDs, duplicate/invalid transitions, and cross-midnight timestamps have explicit expected outcomes.
5. **Only then harden attendance behavior**
   - Use test results to prioritize separate fixes such as elapsed-time calculation, duplicate logout protection, and stale-session recovery.
   - Keep these behavior changes isolated from the initial API extraction so regressions are attributable and the original workflow can be compared.

Phase 1 is implemented with `AttendanceService`, `AttendanceRepository`, and `GoogleSheetsAttendanceRepository`. `UserActivity` keeps the JavaFX messages and interactive registration flow, while the service accepts an injectable `Clock` and can be tested using a fake repository. `.\gradlew.bat test` passes.

Phase 2 code adds `TestDatabaseConfig`, `DatabaseProcess(TestDatabaseConfig)`, and `DatabaseUtils(DatabaseProcess)`. Headless wiring is `new DatabaseUtils(new DatabaseProcess(TestDatabaseConfig.load(Paths.get(testConfigPath))))`. Test mode requires an explicit config path, `"testMode": true`, a non-placeholder `"sheet"` ID, and a `"tokenDirectory"` distinct from the normal `tokens` directory; it does not fall back to `config.json`. Headless OAuth logs a browser authorization URL when no JavaFX `HostServices` instance exists, and Sheets failures propagate as exceptions rather than opening dialogs or exiting the process. `config.test.template.json` is a placeholder-only template; copy it to a local, untracked config and provide a dedicated test spreadsheet ID before any live verification.

The automated config-validation tests and Java build pass. A live Sheets read/write was later verified against the user's confirmed existing test sheet. The `testMode` flag is an explicit operator safeguard, not proof that the spreadsheet itself is non-production; confirm the configured sheet ID before running any integration test.

Phase 3 adds `api.AttendanceApiMain`, `api.AttendanceHttpServer`, and the Gradle `runAttendanceApi` task. It requires `--test-mode` and an explicit `--config`, binds only to loopback, serializes requests, accepts bounded JSON bodies, and exposes health/status/login/logout routes with stable JSON errors. `occurredAt` is optional and, when provided, must be an ISO-8601 timestamp with an offset. Duplicate login/logout transitions return `409`. Unit tests exercise the HTTP contract against a fake repository; live backend/health validation still depends on local OAuth and a dedicated test sheet.

To start it after creating a local test config from `config.test.template.json`, use `.\gradlew.bat runAttendanceApi -PtestConfig=config.test.json` (optionally `-PapiPort=8765`). Do not start it until verifying the sheet ID is a disposable test sheet. Normal JavaFX startup remains on the existing `Main` entry point and does not launch this server.

Phase 4 adds the standard-library Python client and CLI in `python/client.py`, guarded integration coverage in `python/tests/test_attendance_api.py`, and instructions in `python/README.md`. The integration test requires explicit opt-in, explicit test-sheet confirmation, and a pre-seeded, logged-out test user. Client unit tests pass. Live Python login/status/logout integration passed for two existing logged-out users on the user's confirmed test sheet; both users were verified logged out afterward. Java HTTP tests additionally verify malformed timestamps, unknown users, invalid transitions, cross-midnight duration handling, request limits, and loopback binding.

The status endpoint also returns `lastTimeHours` and `totalHours`, read from the user's session-hours and cumulative-hours columns in the `Current` sheet. The Python status command prints these fields in its JSON output.

Run Python client unit tests with `python -m unittest discover -s .\\python -t . -v`. For live integration testing, follow `python/README.md` and set all required environment variables; never point the API at the production sheet.

### Suggested developer workflow

1. Create and seed a dedicated test spreadsheet with the same tabs and column layout.
2. Configure local Google OAuth for that test account; keep tokens and config out of version control.
3. Start the API using the dedicated Gradle task and test config.
4. Run Java unit tests, then Python integration tests against `127.0.0.1`.
5. Confirm the test spreadsheet changed as expected and the production sheet did not.
