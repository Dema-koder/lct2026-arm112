#!/usr/bin/env python3
"""Сравнение этапов эталонных прогонов: качество оценки и стоимость по времени.

    python benchmarks/compare.py                 # все этапы по порядку
    python benchmarks/compare.py 0-baseline 2-issues-db

Читает benchmarks/golden-<этап>.csv, которые пишет GoldenRunHarness.
Зависимостей нет: только стандартная библиотека, чтобы работало в локальном контуре.
"""
import csv
import glob
import io
import os
import sys

DIR = os.path.dirname(os.path.abspath(__file__))


def load(stage):
    path = os.path.join(DIR, "golden-%s.csv" % stage)
    with io.open(path, encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle, delimiter=";"))


def num(value):
    return float((value or "0").replace(",", "."))


def stages():
    found = sorted(glob.glob(os.path.join(DIR, "golden-*.csv")))
    return [os.path.basename(f)[len("golden-"):-len(".csv")] for f in found]


def summary(rows):
    ok = sum(1 for r in rows if r["status"] == "OK")
    return {
        "runs": len(rows),
        "ok": ok,
        "max_delta": max((abs(num(r["total_delta"])) for r in rows), default=0.0),
        "assess_ms": sum(int(r["assess_ms"]) for r in rows),
        "run_ms": sum(int(r["run_ms"]) for r in rows),
        "db_issues": sum(int(r.get("db_issues") or 0) for r in rows),
    }


def main():
    selected = sys.argv[1:] or stages()
    if not selected:
        print("Нет файлов benchmarks/golden-*.csv — сначала прогоните GoldenRunHarness.")
        return 1

    data = {stage: load(stage) for stage in selected}

    print("\n=== Качество и стоимость по этапам ===")
    print("%-18s %6s %8s %10s %12s %10s %10s" % (
        "этап", "прог.", "совпало", "макс.Δ", "оценка,мс", "прогон,мс", "замеч.БД"))
    previous = None
    for stage in selected:
        s = summary(data[stage])
        mark = ""
        if previous:
            delta = s["assess_ms"] - previous["assess_ms"]
            share = 100.0 * delta / previous["assess_ms"] if previous["assess_ms"] else 0.0
            mark = "  %+d мс (%+.0f%%)" % (delta, share)
        print("%-18s %6d %8d %10.1f %12d %10d %10d%s" % (
            stage, s["runs"], s["ok"], s["max_delta"], s["assess_ms"], s["run_ms"], s["db_issues"], mark))
        previous = s

    if len(selected) >= 2:
        first, last = selected[0], selected[-1]
        print("\n=== Что изменилось по прогонам: %s → %s ===" % (first, last))
        before = {r["run_id"]: r for r in data[first]}
        print("%-5s %-38s %9s %9s %9s %9s" % ("ID", "прогон", "балл до", "балл п.", "Δ балла", "Δ оц.мс"))
        for row in data[last]:
            old = before.get(row["run_id"])
            if not old:
                continue
            d_total = num(row["actual_total"]) - num(old["actual_total"])
            d_ms = int(row["assess_ms"]) - int(old["assess_ms"])
            flag = "" if abs(d_total) < 0.05 else "   <-- оценка изменилась"
            print("%-5s %-38s %9.1f %9.1f %+9.1f %+9d%s" % (
                row["run_id"], row["run_name"][:38], num(old["actual_total"]),
                num(row["actual_total"]), d_total, d_ms, flag))

    print("\n=== Расхождения с эталоном на последнем этапе ===")
    mismatched = [r for r in data[selected[-1]] if r["status"] != "OK"]
    if not mismatched:
        print("нет: все прогоны совпали с посчитанным по правилам ожиданием")
    for row in mismatched:
        print("%-5s %s\n      %s" % (row["run_id"], row["run_name"], row["status"]))
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
