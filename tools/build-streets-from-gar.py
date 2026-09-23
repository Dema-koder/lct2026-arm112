#!/usr/bin/env python3
"""Сборка справочника улиц из выгрузки ГАР (ФИАС) для сверки адреса в тренажёре.

Запускается один раз на машине с доступом в интернет; результат кладётся
в src/main/resources/seed/streets.json и едет в поставку вместе со сборкой.

    # 1. Скачать полный архив ГАР (около 35 ГБ) с fias-file.nalog.ru
    #    Актуальную ссылку отдаёт https://fias.nalog.ru/WebServices/Public/GetLastDownloadFileInfo
    # 2. Распаковать только нужный регион: 77 — Москва, 50 — Московская область
    unzip -j gar_xml.zip '77/AS_ADDR_OBJ_*.XML' -d ./gar77

    # 3. Собрать справочник
    python tools/build-streets-from-gar.py ./gar77 src/main/resources/seed/streets.json --regions 77 50

Почему ГАР, а не OpenStreetMap: ГАР — официальный государственный реестр,
открытые данные без ограничений на распространение. OSM распространяется
под ODbL, у которой есть требования к производным базам, и для поставки
в государственную систему это лишний вопрос к юристам.

Что отбирается: элементы улично-дорожной сети (LEVEL=7), действующие
и актуальные. Неактуальные записи — это переименованные и упразднённые
объекты; включать их нельзя, иначе старое название улицы будет засчитано
как верное, а расчёт уедет не туда.
"""
import argparse
import json
import os
import sys
import xml.etree.ElementTree as ET

# Уровень адресного объекта в ГАР: 7 — элемент улично-дорожной сети.
# Уровни 1-6 это регионы, районы и населённые пункты, 8 и далее — здания и помещения.
STREET_LEVEL = "7"


def parse(path, levels):
    """Потоковый разбор: файл региона занимает сотни мегабайт, целиком в память не берём."""
    found = []
    for _, element in ET.iterparse(path, events=("end",)):
        if element.tag != "OBJECT":
            element.clear()
            continue
        attrs = element.attrib
        if (attrs.get("LEVEL") in levels
                and attrs.get("ISACTIVE") == "1"
                and attrs.get("ISACTUAL") == "1"):
            name = (attrs.get("NAME") or "").strip()
            type_name = (attrs.get("TYPENAME") or "").strip()
            if name:
                # «Ленина» + «ул» → «ул Ленина»: тип пишется как в реестре,
                # нормализацию делает уже приложение
                found.append(f"{type_name} {name}".strip() if type_name else name)
        element.clear()
    return found


def main():
    parser = argparse.ArgumentParser(description="Справочник улиц из выгрузки ГАР")
    parser.add_argument("source", help="каталог с файлами AS_ADDR_OBJ_*.XML")
    parser.add_argument("output", help="куда записать streets.json")
    parser.add_argument("--regions", nargs="*", default=["77"],
                        help="коды регионов только для пометки в файле")
    parser.add_argument("--levels", nargs="*", default=[STREET_LEVEL],
                        help="уровни адресных объектов; по умолчанию только улицы")
    args = parser.parse_args()

    files = [os.path.join(args.source, f) for f in sorted(os.listdir(args.source))
             if f.upper().startswith("AS_ADDR_OBJ") and f.upper().endswith(".XML")]
    if not files:
        print(f"В каталоге {args.source} нет файлов AS_ADDR_OBJ_*.XML", file=sys.stderr)
        return 1

    names = set()
    for path in files:
        before = len(names)
        names.update(parse(path, set(args.levels)))
        print(f"  {os.path.basename(path)}: +{len(names) - before}")

    payload = {
        "source": "ГАР (ФИАС), ФНС России",
        "licence": "Открытые данные",
        "attribution": "Государственный адресный реестр, https://fias.nalog.ru",
        "area": "регионы " + ", ".join(args.regions),
        "levels": args.levels,
        "streets": sorted(names),
    }
    with open(args.output, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=1)
        handle.write("\n")

    size = os.path.getsize(args.output) / 1024
    print(f"\nЗаписано {len(names)} названий в {args.output} ({size:.0f} КБ)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
