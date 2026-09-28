#!/usr/bin/env python3
"""Ручные уточнения эталонов билетов поверх tools/build_seed.py.

build_seed.py разбирает docs/materials/tickets.md и угадывает тип и категорию
по подстрокам, а адрес — по запятым. Для генерации сценариев этого мало:
«гор» находится в «горке» и «перегородили», «постр» — в «без пострадавших»,
а в поле «улица» попадает город («Балашиха») или ориентир («МО», «СК»).

Этот шаг идёт после build_seed.py и приводит билеты к виду, на который можно
опираться:

* типы берутся из docs/materials/data/scenario-overrides.json, где они заданы
  вручную по тексту билета;
* службы пересчитываются по матрице типов (seed/service-matrix.json);
* категория выводится из первого типа (seed/incident-types.json), а не из
  ключевых слов, — поэтому «драка» больше не попадает в «медицину»;
* поля адреса из уточнений сливаются с разобранным адресом, null очищает поле.

Банк, написанный заранее вне контура (seed/scenarios-generated.json), проходит
те же пересчёт и проверку, но без файла уточнений: его правят прямо в JSON.

Проверяет, что все типы есть в справочнике, а улицы московских адресов — в
справочнике улиц. Запуск: python tools/build_seed.py && python tools/fix_scenarios.py
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SEED = ROOT / "src" / "main" / "resources" / "seed"
OVERRIDES = ROOT / "docs" / "materials" / "data" / "scenario-overrides.json"

STREET_WORDS = r"(улица|ул|проспект|пр-т|просп|переулок|пер|шоссе|ш|набережная|наб|площадь|пл|бульвар|б-р|проезд|аллея)"


def load(path: Path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def normalize(value: str | None) -> str:
    """Та же нормализация, что TextUtil.normalize на бэкенде: без типа улицы и пунктуации."""
    if not value:
        return ""
    low = value.lower().replace("ё", "е")
    low = re.sub(r"[«»\"'.,;:()\[\]]", " ", low)
    low = re.sub(rf"\b{STREET_WORDS}\b", " ", low)
    return re.sub(r"\s+", " ", low).strip()


def services_for(types: list[str], matrix: dict[str, list[str]]) -> list[str]:
    result: list[str] = []
    for type_id in types:
        for code in matrix.get(type_id, []):
            if code not in result:
                result.append(code)
    return result


def main() -> int:
    tickets = load(SEED / "scenarios.json")
    bank = load(SEED / "scenarios-generated.json")
    overrides = {k: v for k, v in load(OVERRIDES).items() if not k.startswith("_")}
    types = {t["id"]: t for t in load(SEED / "incident-types.json")}
    matrix = load(SEED / "service-matrix.json")
    streets = {normalize(s) for s in load(SEED / "streets.json")["streets"]}

    problems: list[str] = []
    known = {s["id"] for s in tickets}
    problems += [f"{key}: такого билета нет" for key in overrides if key not in known]

    for scenario in tickets + bank:
        fix = overrides.get(scenario["id"], {})
        if "types" in fix:
            scenario["expectedIncidentTypes"] = fix["types"]
        if "address" in fix:
            address = dict(scenario.get("expectedAddress") or {})
            address.update(fix["address"])
            scenario["expectedAddress"] = address

        type_ids = scenario["expectedIncidentTypes"]
        for type_id in type_ids:
            if type_id not in types:
                problems.append(f"{scenario['id']}: типа {type_id} нет в справочнике")
        scenario["expectedServices"] = services_for(type_ids, matrix)
        scenario["category"] = types[type_ids[0]]["category"] if type_ids and type_ids[0] in types else "OTHER"

        address = scenario.get("expectedAddress") or {}
        moscow = address.get("region") in (None, "Москва") and address.get("locality") in (None, "Москва", "Зеленоград")
        if moscow and address.get("street") and normalize(address["street"]) not in streets:
            problems.append(f"{scenario['id']}: улицы «{address['street']}» нет в справочнике")
        if not type_ids:
            problems.append(f"{scenario['id']}: не задан тип")

    for name, data in (("scenarios.json", tickets), ("scenarios-generated.json", bank)):
        with open(SEED / name, "w", encoding="utf-8", newline="") as handle:
            json.dump(data, handle, ensure_ascii=False, indent=2)
            handle.write("\n")

    by_category: dict[str, int] = {}
    for scenario in tickets + bank:
        by_category[scenario["category"]] = by_category.get(scenario["category"], 0) + 1
    print(f"билетов {len(tickets)}, в банке {len(bank)}; по категориям:",
          dict(sorted(by_category.items(), key=lambda item: -item[1])))
    for problem in problems:
        print("ПРОБЛЕМА", problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
