# GrizzlyTime Python API tests

The Python client and integration test use only the Python standard library. The test talks to the Java API, which in turn exercises the attendance service and Google Sheets adapter.

## Safety prerequisites

1. Use a dedicated, non-production spreadsheet with the app's expected tabs and columns. If the existing `config.json` already points to your test sheet, reuse that same sheet; do not create another one.
2. Verify that `config.test.json` points to that existing test sheet and uses a separate token directory.
3. Keep the Java API bound to its default loopback address. It has no authentication and must not be exposed to a network.
4. Do not run attendance integration tests against the classroom production sheet. A login/logout test changes persistent attendance data.

## Start the API

From the repository root, first verify that `config.json` points to the test sheet. You can reuse its sheet ID while adding the explicit test-mode settings to the ignored local `config.test.json`:

```powershell
$testConfig = Get-Content -Raw .\config.json | ConvertFrom-Json
$testConfig | Add-Member -NotePropertyName testMode -NotePropertyValue $true -Force
$testConfig | Add-Member -NotePropertyName tokenDirectory -NotePropertyValue "tokens-test" -Force
$testConfig | ConvertTo-Json | Set-Content .\config.test.json -Encoding UTF8
.\gradlew.bat runAttendanceApi -PtestConfig=config.test.json
```

The config loader accepts PowerShell's UTF-8 BOM. The default listener address is `http://127.0.0.1:8765`. Keep this process running in its terminal while using the client or tests. If OAuth authorization is needed, follow the URL printed by Java and authorize an account that can access the test spreadsheet.

## Use the client

From the repository root:

```powershell
python .\python\client.py health
python .\python\client.py status 001234
python .\python\client.py login 001234
python .\python\client.py logout 001234
```

The status response includes `lastTimeHours` (the most recently recorded session duration) and `totalHours` (the cumulative hours value), along with the user's `state`. Both hours fields are read from the matching columns in the `Current` sheet.

The optional `--occurred-at` argument accepts an ISO-8601 timestamp with an offset. Use it only with the disposable test spreadsheet:

```powershell
python .\python\client.py login 001234 --occurred-at 2026-11-10T12:00:00-08:00
```

Set `GRIZZLYTIME_API_URL` to use a non-default loopback port, for example `$env:GRIZZLYTIME_API_URL = "http://127.0.0.1:8766"`.

## Run the integration test

The test is skipped by default. In PowerShell, explicitly opt in and confirm the test sheet:

```powershell
$env:GRIZZLYTIME_RUN_INTEGRATION = "1"
$env:GRIZZLYTIME_TEST_SHEET_CONFIRMED = "YES"
$env:GRIZZLYTIME_TEST_USER_ID = "<existing-logged-out-test-user-id>"
python -m unittest discover -s .\python -t . -v
```

The selected user must exist in the test spreadsheet and be logged out before the test starts. The test covers health, unknown users, malformed timestamps, login/status/logout, and duplicate transitions. It attempts to log the user out in cleanup if an assertion fails after login. Run the integration module once per selected test user; it writes attendance and hour totals to the sheet.

Without these environment variables, test discovery reports the integration test as skipped and performs no HTTP requests or spreadsheet writes.

## Run Poisson-timed attendance events

With the Java API running, start the simulator from another terminal:

```powershell
python .\python\poisson_process.py --rate 0.02 --max-events 10
```

The rate is expected events per second (so `0.02` averages one event every 50 seconds). For each event, the script chooses an ID from its configured test-user pool and checks its status first. It logs out users already logged in and logs in users who are logged out. If the ID is unknown, the simulator skips that event because the test API does not provide the interactive registration flow. A timeout, connection failure, or HTTP 5xx backend error stops the simulator; it does not continue generating attendance requests. Use only with the confirmed test sheet because the actions update attendance data.
