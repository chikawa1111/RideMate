"""
휴게소 마스터 데이터 만들기 (APIS.md §3).

입력 (tools/raw/)
  ex_service_areas.tsv   한국도로공사 공공데이터 포털(data.ex.co.kr) 휴게소 위치(locationinfoRest) + 편의시설(conveniServiceArea)
  toilets_15142833.csv   공공데이터포털 15142833 장애인·가족사랑 화장실 운영여부
  parking_15043716.csv   공공데이터포털 15043716 휴게소 주차장 현황(장애인 전용 주차면)
  shelters_15028203.csv  공공데이터포털 15028203 졸음쉼터(위경도·화장실)
  pet_playgrounds.csv    반려견 놀이터 운영 휴게소 (공식 파일이 없어 보도자료 기반 수기 작성)

출력
  app/src/main/assets/rest_areas.json
  tools/unmatched.csv    조인 실패 행 (사람이 확인)

실행: python3 tools/build_rest_areas.py
"""
import csv
import json
import os
import re
from datetime import date

ROOT = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(ROOT, "raw")
OUT = os.path.join(ROOT, "..", "app", "src", "main", "assets", "rest_areas.json")

# 원본 좌표가 노선과 맞지 않는 행(지도 확인 결과). 앱에 넣지 않는다.
BAD_COORDS = {"A00262", "A00259", "A00260"}


def split_name(raw: str):
    """'서울만남(부산)휴게소' -> ('서울만남', '부산'), '덕평휴게소' -> ('덕평', '')"""
    s = raw.replace(" ", "").replace("휴게소", "")
    m = re.match(r"^(.*?)\((.*?)\)(.*)$", s)
    if m:
        base, direction = m.group(1) + m.group(3), m.group(2)
    else:
        base, direction = s, ""
    if direction in ("통합", "상", "하"):
        direction = ""
    return base, direction


class Index:
    """(이름, 방향)으로 찾되, 이름이 다르게 적힌 경우(고창 vs 고창고인돌)는 같은 방향 + 앞부분 일치로 찾는다."""

    def __init__(self, rows, name_key):
        self.rows = {}
        for r in rows:
            self.rows.setdefault(split_name(r[name_key]), r)
        self.used = set()

    def find(self, base, direction):
        for key in [(base, direction), (base, "")]:
            if key in self.rows:
                self.used.add(key)
                return self.rows[key]
        for (b, d), r in self.rows.items():
            if d == direction and (b.startswith(base) or base.startswith(b)) and min(len(b), len(base)) >= 2:
                self.used.add((b, d))
                return r
        return None


def read_csv(name):
    with open(os.path.join(RAW, name), encoding="utf-8") as f:
        return list(csv.DictReader(f))


def main():
    with open(os.path.join(RAW, "ex_service_areas.tsv"), encoding="utf-8") as f:
        areas = list(csv.DictReader(f, delimiter="\t"))
    toilets = Index(read_csv("toilets_15142833.csv"), "name")
    parking = Index(read_csv("parking_15043716.csv"), "name")
    pets = Index(read_csv("pet_playgrounds.csv"), "name")

    out, unmatched = [], []
    for a in areas:
        if a["code"] in BAD_COORDS or "임시" in a["name"]:
            continue
        base, direction = split_name(a["name"])
        conv = set(filter(None, (a.get("convenience") or "").split("|")))
        t = toilets.find(base, direction)
        p = parking.find(base, direction)
        pet = pets.find(base, direction)
        if t is None or p is None:
            unmatched.append([a["code"], a["name"], "toilet" if t is None else "", "parking" if p is None else ""])
        out.append({
            "id": a["code"],
            "name": base + "휴게소",
            "direction": direction,
            "type": "SERVICE_AREA",
            "route": a["route"],
            "lat": float(a["lat"]),
            "lng": float(a["lng"]),
            "tel": a.get("tel") or None,
            "facilities": {
                "toilet": True,
                "nursingRoom": "수유실" in conv,
                "familyToilet": None if t is None else t["familyToilet"] == "1",
                "accessibleToilet": None if t is None else t["accessibleToilet"] == "1",
                "accessibleParking": None if p is None else int(p["accessibleParking"]),
                "petPlayground": pet is not None,
                "pharmacy": "약국" in conv,
                "sleepRoom": "수면실" in conv,
            },
        })

    for i, s in enumerate(read_csv("shelters_15028203.csv")):
        out.append({
            "id": f"SH-{i + 1:03d}",
            "name": s["name"] + " 졸음쉼터",
            "direction": s["direction"],
            "type": "SHELTER",
            "route": s["route"],
            "lat": float(s["lat"]),
            "lng": float(s["lng"]),
            "tel": None,
            "facilities": {"toilet": s["toilet"] == "Y"},
        })

    doc = {
        "updated": date.today().isoformat(),
        "sources": [
            "data.ex.co.kr locationinfoRest · conveniServiceArea",
            "data.go.kr 15142833 · 15043716 · 15028203",
            "반려견 놀이터: 보도자료 기반 수기(pet_playgrounds.csv 출처 열)",
        ],
        "items": out,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
    with open(os.path.join(ROOT, "unmatched.csv"), "w", encoding="utf-8", newline="") as f:
        csv.writer(f).writerows([["code", "name", "missing_toilet", "missing_parking"]] + unmatched)

    sa = [o for o in out if o["type"] == "SERVICE_AREA"]
    print(f"휴게소 {len(sa)} · 졸음쉼터 {len(out) - len(sa)} · 조인 실패 {len(unmatched)}"
          f" · 반려견 놀이터 {sum(o['facilities']['petPlayground'] for o in sa)}")


if __name__ == "__main__":
    main()
