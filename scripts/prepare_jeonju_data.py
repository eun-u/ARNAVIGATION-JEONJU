from __future__ import annotations

import argparse
import csv
import hashlib
import json
import shutil
import zipfile
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ARCHIVE = PROJECT_ROOT / "data" / "데이터셋리스트 (1).zip"
DEFAULT_CROSSWALKS = PROJECT_ROOT / "data" / "전국횡단보도표준데이터.csv"
RAW_ROOT = PROJECT_ROOT / "data" / "raw" / "jeonju"
NATIONAL_RAW_ROOT = PROJECT_ROOT / "data" / "raw" / "national" / "crosswalks" / "2026"
PROCESSED_ROOT = PROJECT_ROOT / "data" / "processed"

ARCHIVE_MEMBERS = {
    "데이터셋리스트/보행망/export.geojson": RAW_ROOT / "osm" / "2026" / "export.geojson",
    "데이터셋리스트/보행망/map.osm": RAW_ROOT / "osm" / "2026" / "map.osm",
    "데이터셋리스트/지형지물코드/[별표1] 수치지형도 지형지물 표준코드.xls": (
        RAW_ROOT / "reference" / "수치지형도_지형지물_표준코드.xls"
    ),
}

EXPECTED_CROSSWALK_COLUMNS = (
    "시도명",
    "시군구명",
    "도로명",
    "소재지도로명주소",
    "소재지지번주소",
    "횡단보도관리번호",
    "횡단보도종류",
    "자전거횡단도겸용여부",
    "고원식적용여부",
    "위도",
    "경도",
    "차로수",
    "횡단보도폭",
    "횡단보도연장",
    "보행자신호등유무",
    "보행자작동신호기유무",
    "음향신호기설치여부",
    "녹색신호시간",
    "적색신호시간",
    "교통섬유무",
    "보도턱낮춤여부",
    "점자블록유무",
    "집중조명시설유무",
    "관리기관명",
    "관리기관전화번호",
    "데이터기준일자",
    "제공기관코드",
    "제공기관명",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def extract_selected_sources(archive_path: Path) -> list[dict[str, Any]]:
    extracted: list[dict[str, Any]] = []
    with zipfile.ZipFile(archive_path) as archive:
        names = set(archive.namelist())
        missing = sorted(set(ARCHIVE_MEMBERS) - names)
        if missing:
            raise ValueError(f"archive members are missing: {missing}")
        for member, destination in ARCHIVE_MEMBERS.items():
            destination.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(member) as source, destination.open("wb") as target:
                shutil.copyfileobj(source, target)
            extracted.append(
                {
                    "archive_member": member,
                    "path": destination.relative_to(PROJECT_ROOT).as_posix(),
                    "bytes": destination.stat().st_size,
                    "sha256": sha256(destination),
                }
            )
    return extracted


def is_jeonju(row: dict[str, str]) -> bool:
    city = row.get("시군구명", "").strip()
    road_address = row.get("소재지도로명주소", "").strip()
    lot_address = row.get("소재지지번주소", "").strip()
    provider = row.get("제공기관명", "").strip()
    return city.startswith("전주시") or any(
        "전주시" in value for value in (road_address, lot_address, provider)
    )


def prepare_crosswalks(source_path: Path) -> dict[str, Any]:
    with source_path.open("r", encoding="cp949", newline="") as handle:
        reader = csv.DictReader(handle)
        fieldnames = tuple(reader.fieldnames or ())
        missing = sorted(set(EXPECTED_CROSSWALK_COLUMNS) - set(fieldnames))
        if missing:
            raise ValueError(f"crosswalk columns are missing: {missing}")
        rows = list(reader)

    jeonju_rows = [row for row in rows if is_jeonju(row)]
    NATIONAL_RAW_ROOT.mkdir(parents=True, exist_ok=True)
    raw_copy = NATIONAL_RAW_ROOT / "전국횡단보도표준데이터_50000건.csv"
    shutil.copy2(source_path, raw_copy)

    PROCESSED_ROOT.mkdir(parents=True, exist_ok=True)
    filtered_path = PROCESSED_ROOT / "jeonju_crosswalks.csv"
    with filtered_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(jeonju_rows)

    return {
        "source_path": raw_copy.relative_to(PROJECT_ROOT).as_posix(),
        "source_sha256": sha256(raw_copy),
        "source_encoding": "CP949",
        "source_row_count": len(rows),
        "column_count": len(fieldnames),
        "columns": list(fieldnames),
        "filter": "시군구명 startswith 전주시 OR 주소/제공기관명 contains 전주시",
        "jeonju_row_count": len(jeonju_rows),
        "output_path": filtered_path.relative_to(PROJECT_ROOT).as_posix(),
        "output_encoding": "UTF-8 with BOM",
        "verified": False,
        "graph_update_allowed": False,
        "status": (
            "candidate_rows_pending_review"
            if jeonju_rows
            else "no_jeonju_rows_in_provided_50000_row_export"
        ),
    }


def prepare(archive_path: Path, crosswalk_path: Path) -> dict[str, Any]:
    if not archive_path.exists():
        raise FileNotFoundError(archive_path)
    if not crosswalk_path.exists():
        raise FileNotFoundError(crosswalk_path)

    extracted = extract_selected_sources(archive_path)
    crosswalks = prepare_crosswalks(crosswalk_path)
    manifest = {
        "dataset_id": "jeonju_user_handoff_20260918",
        "source_archive": {
            "path": archive_path.relative_to(PROJECT_ROOT).as_posix(),
            "bytes": archive_path.stat().st_size,
            "sha256": sha256(archive_path),
        },
        "extracted": extracted,
        "excluded_archive_content": [
            {
                "pattern": "*/INNORIX-Agent.exe",
                "reason": "download client executable, not spatial data",
            }
        ],
        "crosswalks": crosswalks,
        "provenance_policy": {
            "verified": False,
            "graph_update_allowed": False,
            "note": "원본 OSM과 공공데이터는 후보 입력이며 현장 검증 전 접근성 사실로 승격하지 않는다.",
        },
    }
    manifest_path = RAW_ROOT / "source_manifest.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description="Prepare the provided Jeonju source package.")
    parser.add_argument("--archive", type=Path, default=DEFAULT_ARCHIVE)
    parser.add_argument("--crosswalks", type=Path, default=DEFAULT_CROSSWALKS)
    args = parser.parse_args()
    result = prepare(args.archive.resolve(), args.crosswalks.resolve())
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
