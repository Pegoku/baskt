#!/usr/bin/env python3
"""Measure an installed baskt build on an unlocked adb device, without clearing its data."""
import argparse
import re
import statistics
import subprocess
import time


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True)


def milliseconds(duration):
    parts = re.fullmatch(r"(?:(\d+)m)?(?:(\d+)s)?(?:(\d+)ms)?", duration)
    if not parts or not any(parts.groups()):
        raise ValueError(f"Unknown Android duration: {duration}")
    minutes, seconds, millis = (int(part or 0) for part in parts.groups())
    return minutes * 60_000 + seconds * 1000 + millis


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runs", type=int, default=5)
    args = parser.parse_args()
    if args.runs < 1:
        parser.error("--runs must be positive")
    results = []
    for run in range(args.runs):
        adb("shell", "am", "force-stop", "nl.baskt")
        time.sleep(0.3)
        since = adb("shell", "date '+%m-%d %H:%M:%S.%3N'").strip()
        launch = adb("shell", "am", "start", "-W", "-n", "nl.baskt/.MainActivity")
        if "LaunchState: COLD" not in launch:
            raise RuntimeError(f"Expected a cold launch; unlock the phone and retry.\n{launch}")
        initial = int(re.search(r"TotalTime: (\d+)", launch)[1])
        deadline = time.monotonic() + 20
        while True:
            logs = adb("logcat", "-d", "-T", since, "-s", "ActivityTaskManager:I")
            matches = re.findall(r"Fully drawn nl\.baskt/\.MainActivity: \+([^\s]+)", logs)
            if matches:
                restored = milliseconds(matches[-1])
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("No fully-drawn report; install a build with ReportDrawnWhen.")
            time.sleep(0.1)
        results.append((initial, restored))
        print(f"Run {run + 1}: first frame {initial} ms; restored screen {restored} ms", flush=True)
        time.sleep(1)
    print(f"Median: first frame {statistics.median(r[0] for r in results):g} ms; "
          f"restored screen {statistics.median(r[1] for r in results):g} ms")


if __name__ == "__main__":
    main()
