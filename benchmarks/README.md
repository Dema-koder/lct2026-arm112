# Эталонные прогоны и замеры

Результаты прогонов десяти симуляций после каждого этапа доработок. Что с этими цифрами делать — в [docs/assessment/BENCHMARK_ANALYSIS.md](../docs/assessment/BENCHMARK_ANALYSIS.md).

## Прогон

```bash
# один этап; имя этапа попадает в имя файла и в колонку stage
mvn test -Dtest=GoldenRunHarness -Dgolden.stage=3-address-reference -Dgolden.commit=$(git rev-parse --short HEAD)

# на машине без локального JDK 21 — через тот же образ, которым собирается продукт
docker run --rm -v "$(pwd):/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace \
  maven:3.9.9-eclipse-temurin-21 mvn -B test -Dtest=GoldenRunHarness -Dgolden.stage=3-address-reference
```

Флаг `-Dgolden.strict=true` валит сборку при расхождении с эталоном. Включать его в CI **после** того, как ожидания в `runs.json` сверены с поведением: на этапе 0 расхождение — это находка, а не обязательно ошибка кода.

## Сравнение этапов

```bash
python benchmarks/compare.py                      # все этапы по порядку
python benchmarks/compare.py 0-baseline 2-issues-db
```

Зависимостей нет — только стандартная библиотека, чтобы работало в локальном контуре.

## Формат файла

`golden-<этап>.csv`, разделитель `;`, десятичный знак — запятая, BOM для Excel. Одна строка на прогон.

| Группа колонок | Колонки | Смысл |
|---|---|---|
| Что прогоняли | `stage`, `run_id`, `run_name`, `mode`, `kind`, `scenario_id` | Идентификация прогона |
| Качество | `expected_total`, `actual_total`, `total_delta`, `expected_criteria`, `actual_criteria`, `criteria_delta_max` | Расхождение с посчитанным по правилам ожиданием |
| Замечания | `expected_issues`, `actual_issues`, `missing_issues`, `unexpected_issues`, `syntax_errors`, `db_issues` | Коды замечаний; `db_issues` — сколько строк реально легло в `assessment_issue` |
| Стоимость | `run_ms`, `setup_ms`, `work_ms`, `assess_ms`, `fetch_ms`, `http_calls` | Тайминги четырёх фаз прогона |
| Происхождение | `status`, `commit`, `recorded_at` | Результат сверки и на каком коммите снято |

Ключевая колонка для производительности — **`assess_ms`**: время от последнего действия обучающегося до готовой оценки. Именно она растёт, когда в оценку добавляют модель.

## Почему CSV, а не Parquet

Ради таблицы на 10 строк пришлось бы тащить `parquet-avro` и `hadoop-common` в тестовые зависимости (~40 МБ) в контур без интернета. При росте набора конвертация делается одной строкой:

```python
import pandas as pd
pd.read_csv("benchmarks/golden-0-baseline.csv", sep=";", decimal=",").to_parquet("golden.parquet")
```

## Что тайминги значат, а что нет

Замеры сняты на H2 в памяти, на одной машине, последовательно. Они **сравнимы между этапами** — это их назначение. Они **не являются** замером продуктивной производительности: там Postgres, сеть класса и 20–30 одновременных сессий. Для этого — отдельный нагрузочный профиль (задача E2 плана).
