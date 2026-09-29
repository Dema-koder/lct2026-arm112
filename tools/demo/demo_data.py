"""Демо-данные на чистом стенде: 1 группа из 10 обучающихся, 6 занятий по 3 билета одной темы.

Всё идёт через настоящий API: карточки заполняются с реальными задержками, оценку считает
оценщик, разборы пишет модель, преподаватель проверяет работы. Поведение обучающихся —
вероятностная модель способности с ростом от занятия к занятию.
"""
import json, math, random, sys, threading, time, uuid, urllib.request, urllib.error

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8082/api/v1"
random.seed(20261001)

LESSONS = [  # (тема, категория, вид)
    ("Пожары", "t101", "TRAINING"),
    ("Медицинская помощь", "t103", "TRAINING"),
    ("Правонарушения", "t102", "TRAINING"),
    ("Человек в опасности", "person_danger", "CHECK"),
    ("Аварии в городском хозяйстве", "accident_utility", "CHECK"),
    ("Газ", "t104", "EXAM"),
]
PEOPLE = [  # ФИО, способность, темп роста
    ("Андреева Мария Сергеевна", 1.9, 0.10), ("Белов Кирилл Андреевич", 1.6, 0.15),
    ("Васильева Екатерина Игоревна", 1.2, 0.25), ("Григорьев Денис Олегович", 1.0, 0.30),
    ("Данилова Ольга Викторовна", 0.9, 0.20), ("Егоров Павел Николаевич", 0.7, 0.35),
    ("Жукова Анна Дмитриевна", 0.6, 0.15), ("Захаров Илья Сергеевич", 0.3, 0.30),
    ("Иванова Светлана Петровна", 0.1, 0.25), ("Козлов Максим Алексеевич", -0.2, 0.20),
]
TEACHER_COMMENTS = {
    "address": ["Адрес записан неполно — сверяйте корпус и дом с уточнённым.", "Улицу записывайте по справочнику, не со слов заявителя."],
    "language": ["Сокращения допустимы, текст понятен.", "Описание читается, мелкие опечатки не критичны."],
    "classification": ["Тип выбран близко, но службы из-за него другие."],
}


def call(method, path, body=None, token=None, key=None):
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Contract-Version", "0.3")
    if token: req.add_header("Authorization", "Bearer " + token)
    if key: req.add_header("Idempotency-Key", key)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try: return e.code, json.loads(raw)
        except Exception: return e.code, raw.decode(errors="replace")


def ok(res, *codes):
    code, body = res
    if code not in (codes or (200, 201, 202, 204)): raise RuntimeError(f"HTTP {code}: {body}")
    return body


def login(u, p):
    return ok(call("POST", "/auth/login", {"username": u, "password": p}))["accessToken"]


def roll(theta, b): return random.random() < 1 / (1 + math.exp(-(theta - b)))


def typo(word):
    if len(word) < 5: return word + "а"
    k = len(word) // 2
    return word[:k] + word[k + 1] + word[k] + word[k + 2:]


def sloppy(text):
    words = text.split()
    for _ in range(2):
        i = random.randrange(len(words))
        if len(words[i]) > 5 and words[i].isalpha() and words[i][0].islower(): words[i] = typo(words[i])
    return " ".join(words).replace(",", ",,", 1)


def main():
    admin, teacher = login("admin", "admin"), login("teacher", "teacher")
    teacher_id = ok(call("GET", "/auth/me", token=teacher))["id"]
    types = ok(call("GET", "/references/incident-types", token=teacher))
    by_cat = {}
    for t in types: by_cat.setdefault(t["category"], []).append(t["id"])
    group = ok(call("POST", "/admin/groups", {"name": "Группа ОП-112 (демо)", "teacherId": teacher_id}, admin))
    trainees = []
    for i, (name, theta, grow) in enumerate(PEOPLE):
        login_name = f"op{i + 1:02d}"
        u = ok(call("POST", "/admin/users", {"login": login_name, "password": "demo2026", "displayName": name,
                                             "role": "TRAINEE", "workstationNumber": str(i + 1), "groupId": group["id"]}, admin))
        trainees.append({"id": u["id"], "login": login_name, "name": name, "theta": theta, "grow": grow})
    print("группа и 10 обучающихся созданы", flush=True)

    library = ok(call("GET", "/teacher/scenarios", token=teacher))
    lessons = []
    for n, (theme, cat, kind) in enumerate(LESSONS):
        def usable(s):
            a = s["expectedAddress"] or {}
            return (s["category"] == cat and a.get("street") and a.get("house")
                    and a.get("locality") in (None, "Москва", "Зеленоград") and a.get("region") in (None, "Москва")
                    and (s["source"] == "TICKET" or s["id"].startswith("gen-seed-")))
        clean = [s for s in library if usable(s)]
        tickets = [s for s in clean if s["source"] == "TICKET"]
        pick = (tickets + [s for s in clean if s["source"] != "TICKET"])[:3]
        assert len(pick) == 3, (cat, len(pick))
        by_id = {s["id"]: s for s in pick}
        lesson = ok(call("POST", "/teacher/lessons", {
            "title": f"Занятие {n + 1}. {theme}", "groupId": group["id"], "kind": kind, "mode": "CARD_FILL",
            "cardSource": "GENERATED", "scenarioIds": [s["id"] for s in pick],
            "traineeIds": [t["id"] for t in trainees]}, teacher))
        ok(call("POST", f"/teacher/lessons/{lesson['id']}/start", token=teacher))
        print(f"\n=== {lesson['title']} ({kind}): {[s['title'] for s in pick]}", flush=True)

        errors = []
        def work(t):
            try:
                tok = login(t["login"], "demo2026")
                theta = t["theta"] + t["grow"] * n
                sid = ok(call("GET", "/trainee/context", token=tok))["activeSession"]["id"]
                time.sleep(random.uniform(0, 15))
                for _ in pick:
                    code, d = call("POST", "/card-drafts", {"sessionId": sid}, tok)
                    if code != 201: break
                    s = by_id[d["scenarioId"]]; exp = s["expectedAddress"]
                    street, house = exp["street"], exp["house"]
                    if not roll(theta, 0.6):
                        k = random.random()
                        if k < .5: street = typo(street)
                        elif k < .85: house = None
                        else: street = random.choice(["Тверская улица", "улица Лескова", "Профсоюзная улица"])
                    addr = {"country": "Россия", "locality": exp.get("locality") or "Москва", "street": street}
                    if house: addr["house"] = house
                    if exp.get("building") and roll(theta, 0.2): addr["building"] = exp["building"]
                    types_ = list(s["expectedIncidentTypes"])
                    if not roll(theta, 0.1):
                        near = [x for x in by_cat.get(cat, []) if x not in types_ and not x.startswith("top.")]
                        types_ = [random.choice(near)] if near and random.random() < 0.6 else [random.choice(["crime.hooligan", "medical.illness", "person.danger"])]
                    desc = s["callerText"] if roll(theta, -0.3) else sloppy(s["callerText"])
                    think = max(35, min(280, random.gauss(150 - 35 * theta, 40)))
                    time.sleep(think * 0.5)
                    ok(call("PATCH", f"/card-drafts/{d['id']}", {"address": addr, "incidentTypeIds": types_}, tok))
                    time.sleep(think * 0.5)
                    ok(call("PATCH", f"/card-drafts/{d['id']}", {"description": desc}, tok))
                    ok(call("POST", f"/card-drafts/{d['id']}/save", None, tok, str(uuid.uuid4())))
            except Exception as e:
                errors.append(f"{t['login']}: {e}")

        threads = [threading.Thread(target=work, args=(t,)) for t in trainees]
        for th in threads: th.start(); time.sleep(0.5)
        for th in threads: th.join()
        for e in errors: print("ОШИБКА", e, flush=True)
        time.sleep(3)
        call("POST", f"/teacher/lessons/{lesson['id']}/complete", token=teacher)

        # проверка преподавателем: согласие с системой или поправка по своей политике
        report = ok(call("GET", f"/teacher/lessons/{lesson['id']}/report", token=teacher))
        graded = 0
        for row in report["rows"]:
            if row.get("aiTotal") is None: continue
            r = random.random()
            if kind != "EXAM" and r > 0.7: continue
            det = ok(call("GET", f"/teacher/sessions/{row['sessionId']}", token=teacher))
            crit = []
            agree = r < (0.5 if kind == "EXAM" else 0.35)
            for c in det["assessment"]["aiCriteria"]:
                if c.get("score") is None: continue
                score, comment = c["score"], "-"
                if not agree:
                    if c["code"] == "address" and score < 100:
                        score = max(0, score * 0.95 - 6 + random.gauss(0, 2)); comment = random.choice(TEACHER_COMMENTS["address"])
                    elif c["code"] == "language" and score < 100:
                        score = min(100, score + 8 + random.gauss(0, 2)); comment = random.choice(TEACHER_COMMENTS["language"])
                    elif c["code"] == "classification" and 0 < score < 100:
                        comment = random.choice(TEACHER_COMMENTS["classification"])
                crit.append({"code": c["code"], "score": round(score, 1), "comment": comment})
            body = {"criteria": crit, "comment": "Согласен с оценкой системы." if agree else "Разобрали на занятии."}
            ok(call("PUT", f"/teacher/sessions/{row['sessionId']}/assessment", body, teacher))
            graded += 1
        if kind == "EXAM":
            ok(call("POST", f"/teacher/lessons/{lesson['id']}/publish", token=teacher))
        report = ok(call("GET", f"/teacher/lessons/{lesson['id']}/report", token=teacher))
        fin = [r["finalTotal"] for r in report["rows"] if r.get("finalTotal") is not None]
        print(f"   итоги: {len(fin)} из 10, средний {sum(fin)/max(1,len(fin)):.1f}, мин {min(fin):.0f}, макс {max(fin):.0f}; проверено преподавателем {graded}", flush=True)
        lessons.append(lesson["id"])
    print("LESSONS", json.dumps(lessons), flush=True)


if __name__ == "__main__":
    main()
