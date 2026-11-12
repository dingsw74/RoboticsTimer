"""Generate Poisson-timed attendance events against the local GrizzlyTime API."""

from __future__ import annotations

import argparse
import math
import random
import sys
import time
from dataclasses import dataclass
from typing import Callable, Sequence

if __package__:
    from python.client import AttendanceApiClient, AttendanceApiError
else:
    from client import AttendanceApiClient, AttendanceApiError


DEFAULT_USER_IDS = ("123456", "1177730", "1177731", "1177732", "1177733", "1177734", "1177735", "1177736", "1177737", "1177738", "1177739")


class ApiUnresponsiveError(RuntimeError):
    """The attendance API could not complete a request."""


@dataclass
class Event:
    index: int      # 0, 1, 2, ...
    time: float     # seconds since the process started
    gap: float      # seconds since the previous event


def run_poisson(
    rate: float,
    on_event: Callable[[Event], None] | None = None,
    *,
    max_events: int | None = None,
    duration: float | None = None,
    seed: int | None = None,
) -> list[Event]:
    """Run a Poisson process with `rate` expected events per second.

    Stops after `max_events` events, after `duration` seconds, or on Ctrl+C.
    Returns the list of events that were triggered.
    """
    if rate <= 0:
        raise ValueError("rate must be positive")

    rng = random.Random(seed)
    start = time.monotonic()
    events: list[Event] = []
    elapsed = 0.0

    while True:
        # Exponential interarrival time with mean 1/rate  ->  Poisson process
        gap = rng.expovariate(rate)
        elapsed += gap

        if duration is not None and elapsed > duration:
            break

        # Sleep until the scheduled moment (absolute time avoids drift)
        delay = (start + elapsed) - time.monotonic()
        if delay > 0:
            time.sleep(delay)

        event = Event(index=len(events), time=elapsed, gap=gap)
        events.append(event)

        if on_event is not None:
            on_event(event)

        if max_events is not None and len(events) >= max_events:
            break

    return events


def print_event(event: Event) -> None:
    print(f"  event #{event.index:<3} at t={event.time:7.3f}s (gap {event.gap:6.3f}s)")


def make_attendance_handler(
    client: AttendanceApiClient,
    user_ids: Sequence[str] = DEFAULT_USER_IDS,
    *,
    seed: int | None = None,
) -> Callable[[Event], None]:
    """Return a callback that mirrors the app's status-based login/logout decision."""
    if not user_ids:
        raise ValueError("At least one user ID is required")
    if any(not user_id.isdigit() for user_id in user_ids):
        raise ValueError("User IDs must contain digits only")

    user_rng = random.Random(seed)

    def handle_event(event: Event) -> None:
        user_id = user_rng.choice(user_ids)
        print_event(event)

        try:
            status = client.status(user_id)
        except AttendanceApiError as error:
            if error.status == 404 and error.code == "USER_NOT_FOUND":
                print(
                    f"    user {user_id}: not found; registration is not supported "
                    "by the test API, skipping event"
                )
                return
            if error.status >= 500:
                raise ApiUnresponsiveError(
                    f"Attendance API returned HTTP {error.status} during status check: {error}"
                ) from error
            raise ApiUnresponsiveError(
                f"Attendance API returned unexpected HTTP {error.status} "
                f"during status check: {error}"
            ) from error
        except (RuntimeError, OSError, ValueError, AttributeError) as error:
            raise ApiUnresponsiveError(
                f"Attendance API did not complete status check for user {user_id}: {error}"
            ) from error

        state = status.get("state")
        if state not in ("logged_in", "logged_out"):
            raise ApiUnresponsiveError(
                f"Attendance API returned an invalid status for user {user_id}: {state!r}"
            )

        action = client.logout if state == "logged_in" else client.login
        action_name = "logout" if state == "logged_in" else "login"
        try:
            response = action(user_id)
            print(
                f"    user {user_id}: {action_name} succeeded "
                f"({response.get('state', 'ok')})"
            )
        except AttendanceApiError as error:
            if error.status >= 500:
                raise ApiUnresponsiveError(
                    f"Attendance API returned HTTP {error.status} during {action_name}: {error}"
                ) from error
            print(
                f"    user {user_id}: {action_name} returned "
                f"{error.status} {error.code}"
            )
        except (RuntimeError, OSError, ValueError, AttributeError) as error:
            raise ApiUnresponsiveError(
                f"Attendance API did not complete {action_name} for user {user_id}: {error}"
            ) from error

    return handle_event


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-r", "--rate", type=float, default=1.0,
                        help="expected events per second (default: 1.0)")
    parser.add_argument("-n", "--max-events", type=int, default=None,
                        help="stop after N events (default: run forever)")
    parser.add_argument("-d", "--duration", type=float, default=None,
                        help="stop after S seconds")
    parser.add_argument("--seed", type=int, default=None,
                        help="random seed, for reproducible runs")
    parser.add_argument(
        "--url",
        default=None,
        help="attendance API base URL (default: GRIZZLYTIME_API_URL or localhost:8765)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=10.0,
        help="seconds to wait for each API response (default: 10)",
    )
    parser.add_argument(
        "--users",
        default=",".join(DEFAULT_USER_IDS),
        help="comma-separated user IDs to choose from (default: 123456,1177730)",
    )
    args = parser.parse_args()

    user_ids = tuple(user_id.strip() for user_id in args.users.split(",") if user_id.strip())
    if not user_ids:
        parser.error("--users must include at least one ID")
    if any(not user_id.isdigit() for user_id in user_ids):
        parser.error("--users must contain numeric IDs separated by commas")
    if not math.isfinite(args.rate) or args.rate <= 0:
        parser.error("--rate must be a positive finite number")
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        parser.error("--timeout must be positive")
    if args.max_events is not None and args.max_events <= 0:
        parser.error("--max-events must be positive")
    if args.duration is not None and (
        not math.isfinite(args.duration) or args.duration <= 0
    ):
        parser.error("--duration must be a positive finite number")

    client = AttendanceApiClient(base_url=args.url, timeout=args.timeout)
    on_event = make_attendance_handler(client, user_ids, seed=args.seed)

    print(f"Poisson process: rate={args.rate} event(s)/s "
          f"(mean gap {1 / args.rate:.3f}s). Ctrl+C to stop.\n")

    try:
        events = run_poisson(
            args.rate,
            on_event=on_event,
            max_events=args.max_events,
            duration=args.duration,
            seed=args.seed,
        )
    except ApiUnresponsiveError as error:
        print(f"\nStopping: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nStopped by user.")
        return 0

    if events and events[-1].time > 0:
        observed = len(events) / events[-1].time
        print(f"\n{len(events)} events in {events[-1].time:.2f}s "
              f"=> observed rate {observed:.2f}/s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
