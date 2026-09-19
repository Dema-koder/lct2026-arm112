"""Собирает сиды бэкенда из разобранных материалов задачи.

Вход:
  docs/materials/tickets.md           — 96 учебных сценариев (билеты)
  docs/materials/data/classifier.json — ЕКП с матрицей оповещения служб

Выход (src/main/resources/seed/):
  scenarios.json      — сценарии из билетов с эталоном (адрес, тип, службы)
  incident-types.json — типы происшествий для плашек и поиска
  service-matrix.json — тип происшествия → службы (по ЕКП)
  services.json       — справочник служб (код, подпись)

Запуск: python tools/build_seed.py   (из корня репозитория)
Файлы пишутся с newline="" и LF — см. history про CRLF.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MATERIALS = ROOT / "docs" / "materials"
OUT = ROOT / "src" / "main" / "resources" / "seed"

# --- Справочник служб: как строка матрицы ЕКП превращается в плитку на панели ---------------
# Порядок важен: первое совпадение по подстроке. Службы вне списка на панель не попадают
# (ФСО, ЦУКБ, ГКУ НТУ и т.п. — их на боевом экране ДДС нет).
SERVICE_RULES: list[tuple[str, str, str]] = [
    ("МЧС", "101", "Служба 101"),
    ("МВД", "102", "Служба 102"),
    ("СМП", "103", "Служба 103"),
    ("МОСГАЗ", "104", "Служба 104"),
    ("Росгвардия", "ROSGVARD", "Росгвардия"),
    ("ЦЭМП", "CEMP", "ЦЭМП"),
    ("ЦОДД", "CODD", "ЦОДД"),
    ("ОАТИ", "OATI", "ОАТИ"),
    ("Мослифт", "MOSLIFT", "Мослифт"),
    ("Мосводоканал", "MOSVODOKANAL", "Мосводоканал"),
    ("Мосводосток", "MOSVODOSTOK", "Мосводосток"),
    ("МОЭК", "MOEK", "МОЭК"),
    ("ОЭК", "OEK", "ОЭК"),
    ("Метро", "METRO", "Метро"),
    ("Мосгортранс", "MOSGORTRANS", "Мосгортранс"),
    ("ЖКХ", "DEP_GKH", "Деп. ЖКХ"),
    ("Гор. Хозяйство", "GOR_HOZ", "Гор. хозяйство"),
    ("МОСБЕЗ", "MOSBEZ", "Мос.Без."),
    ("ТиНАО", "OIV_TINAO", "ОИВ ТиНАО"),
    ("Территориальные ОИВ", "OIV", "Терр. ОИВ"),
    ("Аппарат МЭРА", "MAYOR", "Аппарат Мэра"),
    ("Комитет ветеринарии", "VET", "Ком. ветеринарии"),
    ("Мосэкомониторинг", "ECO", "Мосэкомониторинг"),
    ("Автомобильные дороги", "AUTOROADS", "Автодороги"),
]

# --- Типы происшествий ------------------------------------------------------------------
# lookup — подстрока, по которой в ЕКП ищется представительная строка (final_type, затем p1+p2).
INCIDENT_TYPES = [
    # id, label, category, frequent, significant, lookup, synonyms
    ("fire.garbage", "Пожар: мусор", "FIRE", False, True, "пожар: мусор", ["пожар", "101", "горит", "возгорание", "контейнер"]),
    ("fire.apartment", "Пожар: квартира", "FIRE", False, True, "пожар: квартира", ["пожар", "101", "горит", "квартира"]),
    ("fire.building", "Пожар: здание", "FIRE", False, True, "пожар: административное", ["пожар", "101", "здание"]),
    ("fire.smoke", "Задымление в жилом доме", "FIRE", False, True, "задымление: квартира", ["дым", "задымление", "гарь", "101", "мусоропровод"]),
    ("fire.vehicle", "Пожар: транспортное средство", "FIRE", False, True, "пожар: машина", ["пожар", "машина", "автомобиль", "101"]),
    ("fire.grass", "Пожар: трава, пух", "FIRE", False, False, "пожар: трава", ["трава", "пух", "пожар"]),
    ("fire.alarm", "Сработка пожарной сигнализации", "FIRE", False, False, "пожар: сигнализация", ["сигнализация", "сработка"]),
    ("dtp.injured", "ДТП с пострадавшими", "POLICE", True, True, "ДТП с пострадавшими", ["дтп", "столкнов", "сбил", "наезд", "врезал"]),
    ("dtp.no_injured", "ДТП без пострадавших", "POLICE", False, False, "ДТП без пострада", ["дтп без", "стукнул", "царапин"]),
    ("dtp.water", "Падение транспорта в воду", "RESCUE", False, True, "в воду", ["вода", "утонул", "машина в воде", "падение"]),
    ("explosion", "Взрыв", "FIRE", False, True, "Взрыв", ["взрыв", "хлопок", "101"]),
    ("explosion.threat", "Угроза взрыва", "POLICE", False, True, "Угроза взрыва в общественном", ["угроза", "взрыв", "заминирован", "бомба"]),
    ("gas.smell", "Запах газа в помещении", "GAS", False, True, "Запах газа в помещении", ["газ", "запах", "104"]),
    ("fight", "Драка", "POLICE", True, False, "Драка", ["драка", "дерутся", "избива", "избие", "бьют"]),
    ("crime.robbery", "Грабёж", "POLICE", False, False, "Грабеж", ["грабёж", "грабеж", "ограбили", "украли", "похитили", "затащили", "угнали", "102"]),
    ("crime.hooligan", "Хулиганство, нарушение тишины", "POLICE", False, False, "Мелкое хулиганство", ["хулиган", "102", "нарушение порядка", "шум", "музыка", "поругался", "скандал", "дебошир"]),
    ("medical.trauma", "Травма", "MEDICAL", True, False, "Травма", ["травма", "103", "перелом", "ушиб", "отек", "отёк", "в крови", "кровотечение", "порез", "упал с"]),
    ("medical.illness", "Внезапное заболевание", "MEDICAL", True, False, "Плохо с сердцем", ["плохо", "сердце", "103", "без сознания", "потеря сознания", "скорая", "заболевание", "лекарств", "головн", "задыхается", "астма", "температур", "давление", "рожает", "а/д", "д/р"]),
    ("medical.suicide", "Попытка суицида", "MEDICAL", False, True, "Попытка суицида", ["суицид", "самоубий", "хочет прыгнуть", "повесил"]),
    ("person.danger", "Человек в опасности", "RESCUE", True, True, "Лежит человек", ["в опасности", "спасение", "застрял", "открыть дверь", "не подает признаков", "не подаёт признаков", "лежит человек", "лежит мужчина", "лежит женщина", "инвалид"]),
    ("person.missing", "Пропал человек", "POLICE", False, True, "Пропал человек", ["пропал", "потерялся", "потерял", "заблудил", "ушел из дома", "ушёл из дома", "потерей памяти", "не вернул", "102"]),
    ("person.body", "Обнаружен труп", "POLICE", False, True, "Труп на улице ( в общественном месте)", ["труп", "мертв", "мёртв", "без признаков жизни", "тело"]),
    ("water.rescue", "Происшествие на воде", "RESCUE", False, True, "Тонет", ["тонет", "утопающий", "спасение на воде", "льдин", "плывут", "провалился под лед", "провалился под лёд", "на воде"]),
    ("utility.water", "Прорыв воды, залив", "UTILITY", False, False, "Прорыв воды", ["вода", "залив", "прорыв", "течь", "жкх"]),
    ("utility.elevator", "Застревание в лифте", "UTILITY", False, False, "Застревание в лифте", ["лифт", "застрял", "мослифт"]),
    ("utility.power", "Отключение электроэнергии", "UTILITY", False, False, "Отключение электро", ["свет", "электричество", "отключение"]),
    ("tree.fallen", "Поваленное дерево", "UTILITY", False, False, "Поваленные деревья", ["дерево", "бревно", "ветка", "упало на"]),
    ("manhole.open", "Открытый люк", "UTILITY", False, False, "Открытый люк (дворовая территория)", ["люк", "колодец", "колодца"]),
    ("collapse.threat", "Угроза обрушения", "RESCUE", False, True, "Дом многоквартирный угроза обрушения", ["обрушение", "трещина", "101"]),
    ("animal", "Животные", "OTHER", False, False, "Агрессивное животное", ["собака", "животное", "змея", "кошка"]),
    ("wrong_number", "Ошибочно набран номер", "OTHER", True, False, None, ["ошибочно", "ошибка"]),
    ("cancel", "Отмена вызова", "OTHER", True, False, None, ["отмена"]),
    ("test_call", "Тестовый вызов", "OTHER", True, False, None, ["тест"]),
    ("consult", "Консультация", "OTHER", True, False, None, ["консультация", "справка"]),
    ("foreign", "Вызов на иностранном языке", "OTHER", True, False, None, ["иностранный", "язык", "english"]),
]

CATEGORY_KEYWORDS = {
    "FIRE": ["гор", "пожар", "дым", "возгора", "взрыв", "запах гари"],
    "MEDICAL": ["постр", "травм", "перелом", "ушиб", "без сознания", "плохо", "сердц", "скорая", "кровь", "рожает", "суицид", "упал"],
    "POLICE": ["дерутся", "драка", "ограб", "украл", "угон", "хулиган", "нож", "оруж", "полиц", "пропал", "потерял", "труп", "избив", "поругал", "мошен"],
    "RESCUE": ["в воду", "тонет", "утоп", "застрял", "обруш", "человек в опасности", "спас", "заблокир"],
    "GAS": ["газ"],
    "UTILITY": ["лифт", "залив", "прорыв", "отключ", "свет", "дерево", "люк", "жкх", "канализац"],
}


def norm(value: str) -> str:
    return re.sub(r"\s+", " ", value.replace("ё", "е")).strip().lower()


def service_of(raw: str) -> tuple[str, str] | None:
    for needle, code, label in SERVICE_RULES:
        if needle.lower() in raw.lower():
            return code, label
    return None


def load_classifier() -> list[dict]:
    return json.loads((MATERIALS / "data" / "classifier.json").read_text(encoding="utf-8"))


def find_row(rows: list[dict], lookup: str | None) -> dict | None:
    if not lookup:
        return None
    needle = norm(lookup)
    for row in rows:
        if needle == norm(row.get("final_type", "")):
            return row
    for row in rows:
        if needle in norm(row.get("final_type", "")):
            return row
    for row in rows:
        if needle in norm(" ".join([row.get("p1", ""), row.get("p2", ""), row.get("p3", "")])):
            return row
    for row in rows:
        if needle in norm(row.get("ekp35", "")):
            return row
    return None


def services_for(row: dict | None) -> list[str]:
    if row is None:
        return []
    seen: list[str] = []
    for notify in row.get("notify", []):
        if "нет реагирования" in notify.get("type_in_service", "").lower():
            continue
        match = service_of(notify["service"])
        if match and match[0] not in seen:
            seen.append(match[0])
    return seen


def build_incident_types(rows: list[dict]) -> tuple[list[dict], dict[str, list[str]]]:
    types: list[dict] = []
    matrix: dict[str, list[str]] = {}
    for type_id, label, category, frequent, significant, lookup, synonyms in INCIDENT_TYPES:
        row = find_row(rows, lookup)
        services = services_for(row)
        types.append({
            "id": type_id,
            "label": label,
            "category": category,
            "frequent": frequent,
            "significant": significant,
            "synonyms": synonyms,
            "classifierNumber": row["number"] if row else None,
            "classifierType": row["final_type"] if row else None,
            "surveyCardId": type_id,
        })
        matrix[type_id] = services
    return types, matrix


# --- Билеты ---------------------------------------------------------------------------------

PHONE_RE = re.compile(r"(?:\+?7\s*)?[\(\s]*9[\d\s\-\(\)]{8,}\d")
NAME_RE = re.compile(r"([А-ЯЁ][а-яё\-]+(?:\s+[А-ЯЁ][а-яё\-]+){1,2})")
ITALIC_RE = re.compile(r"\*\(([^)]*)\)\*|\*([^*]+)\*")

MONTHS = "|".join(["ул", "улица", "пер", "переулок", "пр-т", "проспект", "просп", "ш", "шоссе", "наб", "набережная",
                   "пл", "площадь", "б-р", "бульвар", "проезд", "аллея", "туп", "тупик", "дор", "дорога", "линия", "мкр"])


# Порядок важен: длинные формы раньше коротких, однобуквенные — только с точкой,
# иначе «Киевская» превращается в «корпус иевская».
FIELD_PREFIXES = [
    ("code", r"домофон|код"),
    ("house", r"дом|д\.|владение|вл\."),
    ("building", r"корпус|корп\.?|к\."),
    ("structure", r"строение|стр\.?|сооружение|соор\.?|с\."),
    ("apartment", r"квартира|кв\.?|офис|оф\."),
    ("entrance", r"подъезд|под\.?|п\."),
    ("floor", r"этаж|эт\.?"),
]


def parse_expected_address(text: str) -> dict:
    """Разбирает уточнение адреса из билета в формализованные поля. Эвристика, не справочник."""
    address = {"country": "Россия", "region": None, "locality": None, "object": None, "okrug": None,
               "district": None, "street": None, "house": None, "building": None, "structure": None,
               "apartment": None, "entrance": None, "floor": None, "code": None, "descriptive": text.strip()}
    parts = [p.strip() for p in re.split(r"[,;]", text) if p.strip()]
    rest: list[str] = []
    for part in parts:
        low = norm(part)
        if low in ("москва", "г. москва", "г.москва", "город москва"):
            address["locality"] = "Москва"
            continue
        if re.match(r"^(г\.?|город)\s+", low):
            address["locality"] = re.sub(r"^(г\.?|город)\s+", "", part, flags=re.I).strip()
            continue
        if "обл" in low and address["region"] is None:
            address["region"] = part
            continue
        matched = False
        for field, prefix in FIELD_PREFIXES:
            m = re.match(rf"^(?:{prefix})\s*(.+)$", part, flags=re.I)
            if m:
                value = m.group(1).strip()
                if field == "house":
                    # «дом 21 корп. 1» в одной части
                    tail = re.search(r"\s(?:корпус|корп\.?|к\.)\s*(\S+)", value, flags=re.I)
                    if tail:
                        address["building"] = tail.group(1)
                        value = value[: tail.start()].strip()
                    tail = re.search(r"\s(?:строение|стр\.?|с\.)\s*(\S+)", value, flags=re.I)
                    if tail:
                        address["structure"] = tail.group(1)
                        value = value[: tail.start()].strip()
                address[field] = value
                matched = True
                break
        if matched:
            continue
        if re.match(r"^\d+[а-яa-z]?(\s*/\s*\d+)?$", low):
            address["house"] = part
            continue
        rest.append(part)
    # Улица — первая «неразобранная» часть; «ул. Берзарина» → «Берзарина»; «дом 21 корп. 1» внутри одной части
    if rest:
        street = rest[0]
        m = re.search(r"\s(?:дом|д\.)\s*(\S+)", street, flags=re.I)
        if m and address["house"] is None:
            address["house"] = m.group(1)
            street = street[: m.start()].strip()
        m = re.search(r"\s(?:корпус|корп\.?|к\.)\s*(\S+)", street, flags=re.I)
        if m:
            address["building"] = m.group(1)
            street = street[: m.start()].strip()
        m = re.search(r"\s(?:строение|стр\.?|с\.)\s*(\S+)", street, flags=re.I)
        if m:
            address["structure"] = m.group(1)
            street = street[: m.start()].strip()
        # «Ленинградское шоссе 112» — номер дома без слова «дом» в конце
        m = re.search(r"\s(\d+[а-яa-z]?(?:/\d+)?)$", street)
        if m and address["house"] is None and not re.search(r"\d+\s*(км|микрорайон|мкр)", street, flags=re.I):
            address["house"] = m.group(1)
            street = street[: m.start()].strip()
        # «МЖД Киевская 1 км. 2 стр.2» — километровые адреса оставляем как есть
        street = re.sub(rf"^(?:{MONTHS})\.?\s+", "", street, flags=re.I)
        street = re.sub(rf"\s+(?:{MONTHS})\.?$", "", street, flags=re.I)
        address["street"] = street.strip() or None
        if len(rest) > 1 and address["object"] is None:
            address["object"] = ", ".join(rest[1:])
    if address["locality"] is None and address["region"] is None:
        address["locality"] = "Москва"
    return address


def category_of(text: str) -> str:
    low = norm(text)
    for category, keys in CATEGORY_KEYWORDS.items():
        if any(k in low for k in keys):
            return category
    return "OTHER"


def guess_incident_types(text: str, types: list[dict]) -> list[str]:
    low = norm(text)
    category = category_of(text)
    scored: list[tuple[int, str]] = []
    for item in types:
        if item["id"] in ("wrong_number", "cancel", "test_call", "consult", "foreign"):
            continue
        score = 0
        for syn in item["synonyms"]:
            if len(syn) > 2 and norm(syn) in low:
                score += 2 if len(syn) > 4 else 1
        if score and item["category"] == category:
            score += 3
        if score:
            scored.append((score, item["id"]))
    scored.sort(reverse=True)
    return [type_id for _, type_id in scored[:1]]


def parse_tickets(types: list[dict], matrix: dict[str, list[str]]) -> list[dict]:
    text = (MATERIALS / "tickets.md").read_text(encoding="utf-8")
    scenarios: list[dict] = []
    ticket = 0
    for line in text.splitlines():
        header = re.match(r"^## Билет (\d+)", line)
        if header:
            ticket = int(header.group(1))
            continue
        row = re.match(r"^\|\s*(\d)\s*\|\s*(.+?)\s*\|\s*(.+?)\s*\|\s*$", line)
        if not row or ticket == 0:
            continue
        ordinal = int(row.group(1))
        situation = row.group(2).strip()
        address_cell = row.group(3).strip()

        phone = None
        m = PHONE_RE.search(situation)
        if m:
            phone = re.sub(r"\D", "", m.group(0))
            if len(phone) == 10:
                phone = "7" + phone
            situation_wo_phone = (situation[: m.start()] + situation[m.end():]).strip(" ,")
        else:
            situation_wo_phone = situation
        full_name = None
        for cand in reversed(NAME_RE.findall(situation_wo_phone)):
            words = cand.split()
            if all(w[0].isupper() for w in words) and len(words) >= 2 and not any(
                    w.lower() in ("москва", "россия") for w in words):
                full_name = cand
                break
        caller_text = situation_wo_phone
        if full_name:
            caller_text = caller_text.replace(full_name, "").strip(" ,")
        caller_text = re.sub(r",\s*,", ",", caller_text).strip(" ,")

        italic = ITALIC_RE.search(address_cell)
        expected_text = None
        if italic:
            expected_text = (italic.group(1) or italic.group(2) or "").strip()
            raw_address = (address_cell[: italic.start()] + address_cell[italic.end():]).strip(" ,")
        else:
            raw_address = address_cell
        raw_address = raw_address.replace("*", "").strip()
        expected = parse_expected_address(expected_text if expected_text else raw_address)

        category = category_of(situation)
        type_ids = guess_incident_types(situation, types)
        services: list[str] = []
        for type_id in type_ids:
            for code in matrix.get(type_id, []):
                if code not in services:
                    services.append(code)
        scenarios.append({
            "id": f"ticket-{ticket:02d}-{ordinal}",
            "source": "TICKET",
            "ticket": ticket,
            "ordinal": ordinal,
            "category": category,
            "difficulty": 3 if expected_text is None else 5,
            "callerText": caller_text,
            "caller": {"fullName": full_name, "phone": phone},
            "rawAddress": raw_address,
            "expectedAddress": expected,
            "expectedIncidentTypes": type_ids,
            "expectedServices": services,
            "addressClarified": expected_text is not None,
        })
    return scenarios


def write(name: str, data) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    with open(OUT / name, "w", encoding="utf-8", newline="") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def main() -> None:
    rows = load_classifier()
    types, matrix = build_incident_types(rows)
    scenarios = parse_tickets(types, matrix)
    services = [{"code": code, "label": label} for _, code, label in SERVICE_RULES]
    write("incident-types.json", types)
    write("service-matrix.json", matrix)
    write("services.json", services)
    write("scenarios.json", scenarios)
    unresolved = [t["id"] for t in types if t["classifierNumber"] is None and t["category"] != "OTHER"]
    print(f"types={len(types)} scenarios={len(scenarios)} unresolved_types={unresolved}")
    no_type = [s["id"] for s in scenarios if not s["expectedIncidentTypes"]]
    print(f"scenarios_without_type={len(no_type)} {no_type[:10]}")


if __name__ == "__main__":
    main()
