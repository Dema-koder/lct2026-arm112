#!/usr/bin/env python3
"""Сводка по замерам языковых моделей: benchmarks/llm-*.csv в одну таблицу.

    python tools/compare-models.py

Колонки, по которым принимается решение:
  ток/с        скорость генерации — от неё зависит время пачки и разбора
  годность     доля кандидатов, переживших ScenarioValidator
  уник.        сколько текстов из пачки различны; одинаковые бесполезны для библиотеки
  чужие        сколько текстов с иероглифами или латиницей
  разбор       секунд на персональный разбор; ×30 даёт время на класс
  подмена      перепутал ли модель эталонный адрес с введённым — самый опасный брак
"""
import csv
import glob
import io
import os
import sys

DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "benchmarks")


def num(value):
    try:
        return float((value or "0").replace(",", "."))
    except ValueError:
        return 0.0


def main():
    rows = []
    for path in sorted(glob.glob(os.path.join(DIR, "llm-*.csv"))):
        with io.open(path, encoding="utf-8-sig") as handle:
            rows.extend(csv.DictReader(handle, delimiter=";"))
    if not rows:
        print("Нет файлов benchmarks/llm-*.csv — сначала прогоните tools/bench-models.sh")
        return 1

    print("\n%-26s %7s %9s %7s %7s %9s %9s" % (
        "модель", "ток/с", "годность", "уник.", "чужие", "разбор,с", "подмена"))
    print("-" * 82)
    for r in sorted(rows, key=lambda x: -num(x.get("accept_pct"))):
        produced = int(r.get("produced") or 0)
        print("%-26s %7.1f %8.0f%% %4s/%-2d %7s %9.0f %9s" % (
            r["model"][:26], num(r.get("gen_tok_per_s")), num(r.get("accept_pct")),
            r.get("unique", "—"), produced, r.get("with_foreign", "—"),
            num(r.get("debrief_seconds")), r.get("debrief_swapped", "—")))

    print("\nВремя на пачку из 10 и на класс из 30 при тех же скоростях:")
    for r in sorted(rows, key=lambda x: num(x.get("gen_tok_per_s")), reverse=True):
        per = num(r.get("seconds_per_scenario"))
        debrief = num(r.get("debrief_seconds"))
        print("  %-26s пачка %5.0f с   разбор класса %5.0f мин" % (
            r["model"][:26], per * 10, debrief * 30 / 60))

    print("\nПричины отсева:")
    for r in rows:
        print("  %-26s %s" % (r["model"][:26], r.get("rejections", "")))
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
