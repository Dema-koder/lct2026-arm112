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
* категория типа выводится из раздела ЕКП по номеру классификатора: раздел —
  это позиция списка «Что случилось?», с которой оператор начинает карточку
  (раздел 17 — «Человек в опасности», 19 — «Смертельный исход», 22 — «103»…);
  результат пишется в seed/incident-types.json;
* категория сценария — категория первого, главного типа;
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
CLASSIFIER = ROOT / "docs" / "materials" / "data" / "classifier.json"

# Раздел ЕКП (первые цифры номера) → позиция «Что случилось?» (seed/card-types.json).
# ЕКП сам делится так, и оператор выбирает именно позицию, а тип и основная служба
# получаются из ответов опросной карты. Своих категорий поверх этого не придумываем.
SECTION_TO_TOP = {
    "1": "t101", "2": "dtp", "3": "explosion", "4": "terror_threat", "5": "collapse",
    "6": "collapse_threat", "7": "nature", "8": "eco", "9": "accident_hydro",
    "10": "accident_hazard", "11": "hazmat_threat", "12": "accident_transport", "13": "t104",
    "14": "accident_utility", "15": "t102", "16": "road_obstacle", "17": "person_danger",
    "18": "child_danger", "19": "death", "20": "social_help", "21": "animals", "22": "t103",
    "23": "other", "24": "uav",
}
# Разделы, где позиция зависит от подраздела (поле p1 строки ЕКП).
P1_TO_TOP = {
    "Скопление воды": "water_accum", "Радиация": "radiation", "Градусник": "thermometer",
    "Благодарность": "gratitude", "Жалоба": "complaint", "Справка консультация": "consult",
    "Тренировка": "training", "Помощь службам": "help_services",
}


def section_of(number: str) -> str:
    return number[:1] if len(number) == 7 else number[:2]


def category_of_type(item: dict, rows: dict[str, dict], tops: set[str]) -> str:
    """Позиция «Что случилось?» для типа: по разделу ЕКП, иначе по самому типу верхнего уровня."""
    number = item.get("classifierNumber")
    if number and number in rows:
        row = rows[number]
        return P1_TO_TOP.get(row.get("p1", "").strip()) or SECTION_TO_TOP[section_of(number)]
    own = item["id"].removeprefix("top.")
    return own if own in tops else "other"

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
    type_list = load(SEED / "incident-types.json")
    rows = {row["number"]: row for row in load(CLASSIFIER)}
    tops = {top["id"] for top in load(SEED / "card-types.json")}
    for item in type_list:
        item["category"] = category_of_type(item, rows, tops)
    types = {t["id"]: t for t in type_list}
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
        scenario["category"] = types[type_ids[0]]["category"] if type_ids and type_ids[0] in types else "other"

        address = scenario.get("expectedAddress") or {}
        moscow = address.get("region") in (None, "Москва") and address.get("locality") in (None, "Москва", "Зеленоград")
        if moscow and address.get("street") and normalize(address["street"]) not in streets:
            problems.append(f"{scenario['id']}: улицы «{address['street']}» нет в справочнике")
        if not type_ids:
            problems.append(f"{scenario['id']}: не задан тип")

    for name, data in (("scenarios.json", tickets), ("scenarios-generated.json", bank), ("incident-types.json", type_list)):
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
