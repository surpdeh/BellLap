#!/usr/bin/env python3
"""Instrumented-test timing report for the emulator-e2e job.

Reads the connected-test JUnit XML (per-test durations), the phase marks that emulator-e2e.sh writes and the
BellLapTiming logcat lines that tests emit, then writes evidence/test-timings.json and prints a Markdown summary.
Comparing runs is the point: the JSON keeps the run context (boot type, CPU count, merge SHA) beside the numbers.

Usage: test-timings.py <repo root> <phases.tsv> <logcat.txt> <out.json>
Standard library only, so it runs on the bare runner.
"""
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET


def tests(root):
    rows = []
    pattern = os.path.join(root, "**/build/outputs/androidTest-results/connected/**/TEST-*.xml")
    for path in sorted(glob.glob(pattern, recursive=True)):
        module = os.path.relpath(path, root).split("/build/")[0].replace("/", ":")
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in suite.iter("testcase"):
            outcome = "passed"
            if case.find("failure") is not None or case.find("error") is not None:
                outcome = "failed"
            elif case.find("skipped") is not None:
                outcome = "skipped"
            rows.append({
                "module": module,
                "class": case.get("classname", ""),
                "name": case.get("name", ""),
                "seconds": float(case.get("time") or 0),
                "outcome": outcome,
            })
    return sorted(rows, key=lambda r: -r["seconds"])


def phases(path):
    """phases.tsv: `<name>\t<epoch ms>` per line, in order. Returns name -> seconds since the first mark."""
    marks = []
    if os.path.exists(path):
        for line in open(path):
            name, _, ms = line.strip().partition("\t")
            if ms.isdigit():
                marks.append((name, int(ms)))
    if not marks:
        return {}, {}
    t0 = marks[0][1]
    at = {name: round((ms - t0) / 1000, 1) for name, ms in marks}
    spans = {}
    for (a, ta), (b, tb) in zip(marks, marks[1:]):
        spans[f"{a}->{b}"] = round((tb - ta) / 1000, 1)
    return at, spans


def test_marks(path):
    """`BellLapTiming: <test> <phase> <ms>ms` lines from tests, as {test: {phase: seconds}}."""
    out = {}
    if os.path.exists(path):
        for line in open(path, errors="ignore"):
            m = re.search(r"BellLapTiming\s*:\s*(\S+) (\S+) (\d+)ms", line)
            if m:
                out.setdefault(m.group(1), {})[m.group(2)] = int(m.group(3)) / 1000
    return out


def main():
    root, phases_tsv, logcat, out_json = sys.argv[1:5]
    rows = tests(root)
    at, spans = phases(phases_tsv)
    inner = test_marks(logcat)
    context = {k: os.environ.get(v, "") for k, v in {
        "run_id": "GITHUB_RUN_ID", "run_attempt": "GITHUB_RUN_ATTEMPT", "sha": "GITHUB_SHA",
        "ref": "GITHUB_REF", "event": "GITHUB_EVENT_NAME", "runner_os": "ImageOS",
        "runner_image_version": "ImageVersion", "avd_cache_hit": "AVD_CACHE_HIT",
    }.items()}
    context["boot"] = "snapshot" if context["avd_cache_hit"] == "true" else "cold"
    context["cpus"] = os.cpu_count()
    report = {
        "schema": 1,
        "context": context,
        "phases_at_s": at,
        "phase_spans_s": spans,
        "tests_total_s": round(sum(r["seconds"] for r in rows), 1),
        "tests": rows,
        "in_test_phases_s": inner,
    }
    os.makedirs(os.path.dirname(out_json) or ".", exist_ok=True)
    with open(out_json, "w") as f:
        json.dump(report, f, indent=1)

    print("### Instrumented test timings")
    print()
    print(f"Boot: **{context['boot']}**, CPUs: {context['cpus']}, "
          f"tests: {len(rows)} in {report['tests_total_s']} s (sum of per-test times)")
    print()
    if spans:
        print("| Phase | Seconds |")
        print("|---|---|")
        for k, v in spans.items():
            print(f"| {k} | {v} |")
        print()
    print("| Test | Module | Seconds | Result |")
    print("|---|---|---|---|")
    for r in rows:
        print(f"| {r['class'].split('.')[-1]}.{r['name']} | {r['module']} | {r['seconds']:.1f} | {r['outcome']} |")
    if inner:
        print()
        print("| Test | In-test phase | Seconds |")
        print("|---|---|---|")
        for test, ph in inner.items():
            for k, v in ph.items():
                print(f"| {test} | {k} | {v:.1f} |")
    print()
    print(f"Raw data: `{os.path.basename(out_json)}` in the tier2-evidence artifact.")


if __name__ == "__main__":
    main()
