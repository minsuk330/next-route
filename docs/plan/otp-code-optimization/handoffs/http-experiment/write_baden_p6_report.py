#!/usr/bin/env python3
"""Render the completed Baden P6 analyzer artifacts as a reviewable report.

This intentionally reports environment-invalid runs and never turns them into
PR evidence.  It is experiment tooling only; it is not part of OTP.
"""

from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path


PAIR_ORDER = [
    ("01", "A", "H"),
    ("02", "H", "A"),
    ("03", "H", "A"),
    ("04", "A", "H"),
    ("05", "A", "H"),
    ("06", "H", "A"),
]

# Semantic roles are content identities, not the legacy JAR basenames.
A_SHA = "2e1808e38a84664ffe8b976aef77023bf9240b2726778020ad968224f1f2fdc6"
H_SHA = "3d583ae579dd1d1d89e8dbd3c7983a89b82c96381c3daf38b26201cc06385b7d"


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def status_map(status: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in status.splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            result[key] = value
    return result


def semantic_variant(status: dict[str, str]) -> str | None:
    """Classify A (flat upstream) vs H (TripTimes/offset optimized) by SHA."""
    digest = status.get("jarSha256", "")
    if digest == A_SHA:
        return "A"
    if digest == H_SHA:
        return "H"
    jar = status.get("jar", "")
    if jar.endswith("/otp-h-only.jar"):
        return "A"
    if jar.endswith("/otp-baseline.jar"):
        return "H"
    return None


def semantic_role(status: dict[str, str]) -> str:
    role = status.get("role", "—")
    variant = semantic_variant(status)
    if variant is None or role == "—":
        return role
    prefix = re.sub(r"-(?:A|H)-(?:baseline|only)$", "", role)
    suffix = "baseline" if variant == "A" else "only"
    return f"{prefix}-{variant}-{suffix}"


NMT_CATEGORIES = ("Total", "Java Heap", "Class", "Thread", "Code", "GC", "Other")
NMT_RE = re.compile(
    r"^\s*-?\s*(?P<name>Java Heap|Class|Thread|Code|GC|Other|Total):?\s*"
    r"\(?(?:reserved=)(?P<reserved>[0-9]+)MB,\s*committed=(?P<committed>[0-9]+)MB",
    re.MULTILINE,
)


def read_nmt(path: str | None) -> dict[str, int]:
    """Read the stable top-level committed values from an NMT snapshot."""
    if not path:
        return {}
    try:
        text = Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return {}
    values: dict[str, int] = {}
    for match in NMT_RE.finditer(text):
        values[match.group("name")] = int(match.group("committed"))
    return values


def nmt_before_after(summary: dict) -> tuple[dict[str, int], dict[str, int]]:
    status = status_map(summary.get("status", ""))
    return read_nmt(status.get("nmtBefore")), read_nmt(status.get("nmtAfter"))


def f(value: object, digits: int = 1) -> str:
    if value is None:
        return "—"
    if isinstance(value, (float, int)):
        return f"{value:.{digits}f}"
    return str(value)


def run_line(name: str, summary: dict, integrity: dict, overlap: dict) -> str:
    status = status_map(summary.get("status", ""))
    req = summary.get("requests", {})
    transit = req.get("transitService", {})
    process = summary.get("process", {})
    rss = process.get("rssMiB", {})
    heap = process.get("heapUsedMiB", {})
    gc = summary.get("gc", {})
    host = summary.get("host", {})
    valid = integrity.get("valid", False)
    host_ok = all((host.get(key) or {}).get("delta") == 0 for key in ("Pageouts", "Swapins", "Swapouts"))
    gate = "PASS" if valid and host_ok and gc.get("fullPauseEvents", 0) == 0 else "ENVIRONMENT_INVALID"
    return (
        f"| {name} | {semantic_role(status)} | {req.get('n', '—')} | "
        f"{('OK' if valid else 'FAIL')} | {f(transit.get('p50Ms'))}/{f(transit.get('p95Ms'))}/{f(transit.get('p99Ms'))} | "
        f"{f(rss.get('median'))}/{f(rss.get('p95'))}/{f(rss.get('max'))} | "
        f"{f(heap.get('EU', {}).get('max'))}/{f(heap.get('OU', {}).get('max'))} | "
        f"{gc.get('pauseEvents', '—')}/{f(gc.get('pauseSumMs'))}/{gc.get('fullPauseEvents', '—')} | "
        f"{host.get('Pageouts', {}).get('delta', '—')}/{host.get('Swapins', {}).get('delta', '—')}/"
        f"{host.get('Swapouts', {}).get('delta', '—')} | {gate} |"
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    summaries = load(args.root / "p6-run-summaries.json")
    bootstrap = load(args.root / "p6-paired-bootstrap.json")
    overlap = load(args.root / "p6-gc-overlap.json")
    integrity = load(args.root / "p6-integrity.json")

    lines: list[str] = []
    lines.extend(
        [
            "# Baden-Württemberg A/H HTTP P6 결과 (소스 역할 기준)",
            "",
            f"생성 시각: {datetime.now(timezone.utc).isoformat(timespec='seconds')}",
            "",
            "상태: `USER_STOPPED_PARTIAL / ENVIRONMENT_CONTAMINATED / DIAGNOSTIC_ONLY`",
            "P6 원래 계약(6 pair/12 fresh JVM)은 사용자의 중단 요청으로 완료되지 않았다. "
            "raw `pair06-H`(semantic A 원본)는 17,602/20,000건에서 중단됐고 raw `pair06-A` 및 "
            "pair01 교정 pair는 실행하지 않았다. "
            "따라서 아래 bootstrap은 완료된 pair만 사용한 진단값이며 PR 성능 증거가 아니다.",
            "",
            "## 결론",
            "",
            "이 문서는 공식 Baden-Württemberg `travelSearch.csv`에서 파생한 동일 HTTP workload의 "
            "진단 결과다. 각 variant의 correctness와 지표를 보존하지만, host swap/page I/O가 "
            "한 번이라도 발생한 pair는 사전 규칙에 따라 PR 성능 증거에서 제외한다.",
            "",
            "역할 정의: A=upstream 원본(`arrivalTimes[]`/`departureTimes[]` 평탄 배열 복사), "
            "H=최적화(`TripTimes[]`/`offsetsByTrip` 참조). legacy JAR basename은 역할 근거가 아니다.",
            "",
        ]
    )

    pair_rows: list[dict] = bootstrap.get("pairs", [])
    no_pressure_pairs = 0
    mismatch_total = 0
    http_errors = 0
    transport_errors = 0
    for row in pair_rows:
        mismatch_total += int(row.get("fingerprintMismatch", 0))
        for key in ("first", "second"):
            name = row.get("pair")
            # pair rows use role labels; resolve the actual summary by the
            # pair's recorded paths when possible.
            candidates = [key]
        # Count no-pressure only when both actual run summaries satisfy host gate.
        pair = str(row.get("pair"))
        first_role, second_role = row.get("first"), row.get("second")
        first_name = f"pair{pair}-{first_role}"
        second_name = f"pair{pair}-{second_role}"
        if pair == "01":
            first_name = "pair01-correction-A"
            second_name = "pair01-correction-H"
        first = summaries.get(first_name, {})
        second = summaries.get(second_name, {})
        if first and second:
            for data in (first, second):
                req = data.get("requests", {})
                http_errors += int(req.get("httpStatusErrors", 0))
                transport_errors += int(req.get("transportErrors", 0))
            if all(
                all((data.get("host", {}).get(key) or {}).get("delta") == 0 for key in ("Pageouts", "Swapins", "Swapouts"))
                and data.get("gc", {}).get("fullPauseEvents", 0) == 0
                for data in (first, second)
            ):
                no_pressure_pairs += 1

    if no_pressure_pairs == len(pair_rows) and pair_rows:
        lines.append("`NO_PRESSURE_CANDIDATE`: 모든 pair가 host/GC gate를 통과했다.")
    else:
        lines.append(
            f"`ENVIRONMENT_CONTAMINATED / DIAGNOSTIC_ONLY`: no-pressure pair {no_pressure_pairs}/{len(pair_rows)}. "
            "swap/pageout이 있는 run의 latency ratio는 PR 근거로 사용하지 않는다."
        )
    lines.extend(
        [
            "",
            f"- pair-level fingerprint mismatch 합계: **{mismatch_total}**",
            f"- HTTP status error 합계: **{http_errors}**, transport error 합계: **{transport_errors}**",
            "- 공식 expected CSV와의 불일치는 SpeedTest provenance 문제로 별도 기록하며 HTTP A/H 정합성과 섞지 않는다.",
            "",
            "## 실행 조건",
            "",
            "- native Java 26.0.2.1, Apple M3 Pro 18 GiB, `-Xms512m -Xmx10g -XX:+UseG1GC`, NMT summary",
            "- 동일 graph/corpus/schedule, open-loop 8 requests/s, 32 workers, 20,000 requests/run",
            "- Docker 미사용, fresh JVM, 측정 중 명시적 GC 없음",
            "- graph SHA-256 `64ee49f2d24243ab21f88225d47f44aeab17017bb6a9b28a2d8d6e476b412bfc`",
            "- corpus SHA-256 `42c6f861a704b1c6a246b2a55506267be1d4c4c49cb6f11ec8e2a962d0bef65c`",
            "- A upstream baseline JAR SHA-256 `2e1808e38a84664ffe8b976aef77023bf9240b2726778020ad968224f1f2fdc6` (legacy `otp-h-only.jar`)",
            "- H optimized JAR SHA-256 `3d583ae579dd1d1d89e8dbd3c7983a89b82c96381c3daf38b26201cc06385b7d` (legacy `otp-baseline.jar`)",
            "",
            "## Run별 결과",
            "",
            "`transit p50/p95/p99`, RSS `median/p95/max`, heap `EUmax/OUmax`, GC `events/pause-ms/full`, "
            "host `Pageouts/Swapins/Swapouts`.",
            "",
            "| run | role | requests | integrity | transit p50/p95/p99 (ms) | RSS median/p95/max (MiB) | heap EUmax/OUmax (MiB) | GC events/sum/full | host Pageouts/Swapins/Swapouts | gate |",
            "|---|---|---:|---|---|---|---|---|---|---|",
        ]
    )

    # Keep the fixed pair order, then include any correction runs.
    names: list[str] = []
    for pair, first, second in PAIR_ORDER:
        if pair == "01":
            names.extend(["pair01-correction-A", "pair01-correction-H"])
        else:
            names.extend([f"pair{pair}-{first}", f"pair{pair}-{second}"])
    for name in summaries:
        if name not in names:
            names.append(name)
    for name in names:
        if name in summaries:
            lines.append(run_line(name, summaries[name], integrity.get(name, {}), overlap.get(name, {})))

    lines.extend(
        [
            "",
            "## NMT 메모리 분해",
            "",
            "NMT snapshot의 top-level committed 값(MB)이다. `before/after`는 예열 직전과 "
            "측정 직후이며, RSS와 동일한 값이 아니고 Java heap의 live set을 뜻하지 않는다.",
            "",
            "| run | role | Total | Java Heap | Class | Thread | Code | GC | Other |",
            "|---|---|---:|---:|---:|---:|---:|---:|---:|",
        ]
    )
    for name in names:
        if name not in summaries:
            continue
        before, after = nmt_before_after(summaries[name])
        role = semantic_role(status_map(summaries[name].get("status", "")))
        cells = []
        for category in NMT_CATEGORIES:
            if category in before or category in after:
                cells.append(f"{before.get(category, '—')}/{after.get(category, '—')}")
            else:
                cells.append("—")
        lines.append(f"| {name} | {role} | " + " | ".join(cells) + " |")

    lines.extend(["", "## Pair bootstrap (transit service, H/A)", "", "| 지표 | H/A geometric mean | 95% CI |", "|---|---:|---:|"])
    for key in ("p50Ms", "p95Ms", "p99Ms", "maxMs"):
        item = bootstrap.get("bootstrapTransitHOverA", {}).get(key, {})
        estimate = item.get("estimate")
        ci = item.get("ci95", [None, None])
        effect = (1 - estimate) * 100 if estimate else None
        effect_text = f"{effect:+.1f}%" if effect is not None else "—"
        label = f"{key} (H/A; {effect_text})"
        lines.append(f"| {label} | {f(estimate, 4)} | {f(ci[0], 4)}–{f(ci[1], 4)} |")

    lines.extend(
        [
            "",
            "## 역할 교정에 따른 해석",
            "",
            "위 H/A는 JAR SHA로 재분류한 semantic role 기준이다. ratio가 1보다 작으면 "
            "H 최적화(`TripTimes[]`/`offsetsByTrip`)가 A 원본(평탄 배열)보다 빠르다는 뜻이다. "
            "`pair*-A`/`pair*-H` raw 접미사와 legacy basename은 이 방향을 결정하지 않는다.",
            "",
            "이 결과는 완료 4 pair만의 진단 bootstrap이며 모든 run에서 host pageout/swap이 "
            "발생했다. 따라서 방향성이 H 우세로 보이더라도 PR 성능 증거로 승격하지 않는다.",
        ]
    )

    lines.extend(
        [
            "",
            "## 해석 제한",
            "",
            "- bootstrap의 독립 단위는 request가 아니라 fresh-JVM pair다. 단, host gate 실패 pair는 "
            "진단값으로만 표시한다.",
            "- `Swapins`/`Pageouts`는 macOS 호스트 전체 counter이며 OTP 전용이 아니다. non-zero는 "
            "압박의 존재를 보수적으로 표시할 뿐 원인을 단정하지 않는다.",
            "- RSS/heap은 graph residency와 JVM 관리 영역을 포함하며, 라우팅 객체의 live set으로 과장하지 않는다.",
            "- 한 Baden graph와 한 Apple host의 결과를 모든 OTP graph/OS/CPU에 일반화하지 않는다.",
            "",
            "## 원자료",
            "",
            f"- analyzer root: `{args.root}`",
            "- run별 `server.log`, `requests.jsonl`, `gc.log`, `process.csv`, `pressure-events.log`, "
            "NMT/vm_stat snapshot과 analyzer JSON을 root에 보존한다.",
            "- `/private/tmp`는 비영속 경로이며, archival script가 완료한 뒤 `.artifacts/otp-baden-p6/`의 "
            "압축본과 `SHA256SUMS.txt`를 함께 사용한다.",
            "- 사용자 중단 시점 raw는 `.artifacts/otp-baden-p6/`에 `archiveMode=partial-stop`으로 "
            "보존되었고, 압축본 SHA-256 검증을 완료했다.",
            "",
        ]
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(lines), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
