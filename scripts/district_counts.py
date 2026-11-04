#!/usr/bin/env python3
"""구별 적재 건수 추출 (RGN6-110 보조자료 → 4-11-03 결과표).

적재 파일(supported-districts.json.gz)의 구·자료 종류별 행 수, 좌표 범위, 출처·내려받은 날짜·기준일을 뽑고,
DB에 실제로 들어간 행 수와 나란히 놓아 결과표를 만든다. 0건인 칸은 '자료 부족'으로 적는다.

DB 행 수는 둘 중 하나로 넣는다(둘 다 없으면 DB 칸은 비워 둔다).
  --coverage-json  : 서버의 GET /api/data/coverage 응답을 저장한 파일
  --db-csv         : scripts/district_counts.sql 결과를 district,kind,rows CSV로 저장한 파일

사용 예:
  python3 scripts/district_counts.py --out build/district-counts
  python3 scripts/district_counts.py --coverage-json coverage.json --out build/district-counts
결과: <out>.md(결과표), <out>.csv(같은 내용), 화면에도 요약을 찍는다.
"""
import argparse
import csv
import gzip
import json
from collections import defaultdict
from pathlib import Path

DEFAULT_DATA = Path(__file__).resolve().parent.parent / "src/main/resources/data/supported-districts.json.gz"
KINDS = ["CROSSWALK", "SCHOOL", "SCHOOL_ZONE", "CCTV", "EMERGENCY_BELL", "SECURITY_LIGHT"]
LABEL = {"CROSSWALK": "횡단보도", "SCHOOL": "학교", "SCHOOL_ZONE": "어린이보호구역(대상시설 위치)", "CCTV": "CCTV",
         "EMERGENCY_BELL": "안전비상벨", "SECURITY_LIGHT": "보안등"}
RISK_LABEL = {"CHILD_ACCIDENT_SCHOOLZONE": "보호구역 안 어린이 사고", "CHILD_PEDESTRIAN_ACCIDENT": "보행 어린이 사고",
              "ELDERLY_PEDESTRIAN_ACCIDENT": "노인 보행자 사고"}


def load_db_rows(args):
    """(구, 종류) → DB 행 수. 자료가 없으면 빈 dict."""
    rows = {}
    if args.coverage_json:
        body = json.loads(Path(args.coverage_json).read_text(encoding="utf-8"))
        body = body.get("response", body)  # 응답 예시 파일 형식도 받는다
        for c in body.get("coverage", []):
            rows[(c["district"], c["kind"])] = int(c["rows"])
    if args.db_csv:
        with open(args.db_csv, encoding="utf-8") as f:
            for r in csv.DictReader(f):
                rows[(r["district"], r["kind"])] = int(r["rows"])
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", default=str(DEFAULT_DATA))
    ap.add_argument("--coverage-json")
    ap.add_argument("--db-csv")
    ap.add_argument("--out", default="district-counts")
    args = ap.parse_args()

    with gzip.open(args.data, "rt", encoding="utf-8") as f:
        d = json.load(f)
    sources = {s["kind"]: s for s in d["sources"]}
    db = load_db_rows(args)

    stat = defaultdict(lambda: {"n": 0, "lat": [90, -90], "lng": [180, -180], "base": set()})
    for f in d["facilities"]:
        s = stat[(f["district"], f["kind"])]
        s["n"] += 1
        s["lat"] = [min(s["lat"][0], f["lat"]), max(s["lat"][1], f["lat"])]
        s["lng"] = [min(s["lng"][0], f["lng"]), max(s["lng"][1], f["lng"])]
        if f.get("baseDate"):
            s["base"].add(f["baseDate"])

    header = ["구", "자료 종류", "적재 파일 행 수", "DB 행 수", "일치", "위도 범위", "경도 범위", "출처", "원본 내려받은 날짜", "기준일 범위", "상태"]
    table = []
    for district in d["districts"]:
        for kind in KINDS:
            s = stat.get((district, kind))
            n = s["n"] if s else 0
            dbn = db.get((district, kind))
            src = sources.get(kind, {})
            base = sorted(s["base"]) if s else []
            table.append([
                district, LABEL[kind], n,
                "" if dbn is None else dbn,
                "" if dbn is None else ("일치" if dbn == n else "불일치"),
                f'{s["lat"][0]:.5f}~{s["lat"][1]:.5f}' if n else "-",
                f'{s["lng"][0]:.5f}~{s["lng"][1]:.5f}' if n else "-",
                src.get("source", ""), src.get("downloaded", ""),
                (base[0] if len(base) == 1 else f"{base[0]}~{base[-1]}") if base else "원본에 없음",
                "적재" if n else "자료 부족",
            ])

    risk = defaultdict(int)
    for a in d["riskAreas"]:
        risk[(a["district"], a["kind"])] += 1

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out.with_suffix(".csv"), "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(table)

    lines = [f"# 구별 적재 건수 결과표 (적재 파일 생성일 {d['generatedAt']})", "",
             "| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    lines += ["| " + " | ".join(str(c) for c in row) + " |" for row in table]
    lines += ["", "## 자료 종류별 합계", "", "| 자료 종류 | 적재 파일 행 수 | 자료 부족 구 |", "|---|---|---|"]
    for kind in KINDS:
        total = sum(stat[(g, kind)]["n"] for g in d["districts"] if (g, kind) in stat)
        missing = [g for g in d["districts"] if (g, kind) not in stat]
        lines.append(f"| {LABEL[kind]} | {total} | {', '.join(missing) if missing else '없음'} |")
    lines += ["", "## 사고 다발 지역(면 자료)", "", "| 구 | " + " | ".join(RISK_LABEL.values()) + " |", "|---|" + "---|" * len(RISK_LABEL)]
    for g in d["districts"]:
        lines.append(f"| {g} | " + " | ".join(str(risk.get((g, k), 0)) for k in RISK_LABEL) + " |")
    lines += ["", f"출처: {d['riskSource']['source']} ({d['riskSource']['reference']})", "",
              "## 적재하지 않은 원본 행", ""]
    lines += [f"- {LABEL.get(x['kind'], x['kind'])}: {x['rows']}행 ({x['reason']}, 구를 알 수 없어 구별 표에 들어가지 않음)" for x in d["dropped"]]
    lines += ["", f"신호: {d['signalNote']}"]
    if not db:
        lines += ["", "DB 행 수 칸이 비어 있으면 --coverage-json 또는 --db-csv로 다시 실행해 채운다."]
    out.with_suffix(".md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    no_data = [r for r in table if r[-1] == "자료 부족"]
    mismatch = [r for r in table if r[4] == "불일치"]
    print(f"{len(d['districts'])}개 구 × {len(KINDS)}종 = {len(table)}칸, 자료 부족 {len(no_data)}칸, DB 불일치 {len(mismatch)}칸")
    print(f"결과: {out.with_suffix('.md')}, {out.with_suffix('.csv')}")


if __name__ == "__main__":
    main()
