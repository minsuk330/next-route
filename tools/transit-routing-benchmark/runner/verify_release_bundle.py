#!/usr/bin/env python3
"""릴리즈 번들 원자성 검증기(P3 PR-B2). release-manifest.json 이 가리키는 5개 산출물이 한 세트인지 확인한다.

형상(SHAPE_OK)이 맞으려면 5개가 동시에 정합해야 한다:
  ① OTP 그래프 graph.obj      ② OTP 런타임 이미지 digest
  ③ shaped GTFS(shapes 포함)   ④ coverage-index.json
  ⑤ 앱 graphVersion(설정과 일치해야 앱이 형상을 실제로 씀)

하나만 바꾸고 나머지를 안 바꾸면(부분 스왑) 앱이 A 그래프 shape 를 B 인덱스로 판정해 오마킹한다.
이 스크립트는 그 조합을 배포 전에 잡는다:

* ``verify`` (기본): 실제 파일(graph/shaped-gtfs/coverage-index)을 재해시해 manifest 기록과 대조하고,
  5개 identity 로 bundleHash 를 재계산해 manifest.releaseBundle.bundleHash 와 비교. 하나라도 어긋나면 exit 1.
* ``--write``: bundleHash + identity 를 manifest 에 기록/갱신(빌드 직후 1회).

stdlib 전용. 파일 sha 는 실측, 이미지 digest·graphVersion 은 manifest 문자열(오프라인 pin)로 취급.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

# manifest 경로(=tools/transit-routing-benchmark)를 기준으로 상대경로 파일을 찾는다.
BENCH_ROOT = Path(__file__).resolve().parents[1]


def _sha256_file(rel_path: str) -> str:
    path = BENCH_ROOT / rel_path
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _identity(manifest: dict) -> list[tuple[str, str]]:
    """bundleHash 를 구성하는 (라벨, 값) 5종. 순서 고정(해시 재현성)."""
    return [
        ("graphVersion", manifest["graphVersion"]),
        ("otpImage", manifest["otp"].get("imageDigest") or manifest["otp"]["image"]),
        ("shapedGtfsSha256", manifest["inputs"]["gtfsShapedSha256"]),
        ("coverageIndexSha256", manifest["shapeGate"]["coverageIndexSha256"]),
        ("graphSha256", manifest["graph"]["sha256"]),
    ]


def _bundle_hash(manifest: dict) -> str:
    payload = "\n".join(f"{label}={value}" for label, value in _identity(manifest))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _check_files(manifest: dict) -> list[str]:
    """실제 파일 재해시 vs manifest 기록. 불일치 메시지 목록(빈=정합)."""
    problems: list[str] = []
    checks = [
        (manifest["graph"]["file"], manifest["graph"]["sha256"], "graph.obj"),
        (manifest["inputs"]["gtfsShapedZip"], manifest["inputs"]["gtfsShapedSha256"], "shaped-gtfs"),
        (manifest["shapeGate"]["coverageIndex"], manifest["shapeGate"]["coverageIndexSha256"],
         "coverage-index"),
    ]
    for rel, recorded, label in checks:
        path = BENCH_ROOT / rel
        if not path.exists():
            problems.append(f"{label}: 파일 없음 {rel}")
            continue
        actual = _sha256_file(rel)
        if actual != recorded:
            problems.append(f"{label}: sha 불일치\n    manifest {recorded}\n    actual   {actual}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest", type=Path,
        default=BENCH_ROOT / "data/otp-seoul/release-manifest.json")
    parser.add_argument(
        "--write", action="store_true",
        help="bundleHash + identity 를 manifest 에 기록(빌드 직후). 미지정=검증만.")
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text())
    bundle_hash = _bundle_hash(manifest)
    identity = {label: value for label, value in _identity(manifest)}

    if args.write:
        manifest["releaseBundle"] = {
            "note": "graph+otpImage+shapedGtfs+coverageIndex+graphVersion 한 세트. "
                    "verify_release_bundle.py 로 배포 전 검증(부분 스왑 차단).",
            "identity": identity,
            "bundleHash": bundle_hash,
        }
        args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        print(f"wrote releaseBundle.bundleHash={bundle_hash}")
        return 0

    # verify
    problems = _check_files(manifest)
    recorded = manifest.get("releaseBundle", {}).get("bundleHash")
    if recorded is None:
        problems.append("releaseBundle.bundleHash 없음 (--write 로 먼저 기록 필요)")
    elif recorded != bundle_hash:
        problems.append(f"bundleHash 불일치\n    manifest {recorded}\n    computed {bundle_hash}")

    if problems:
        print("RELEASE BUNDLE 불일치 — 배포 중단:", file=sys.stderr)
        for p in problems:
            print(f"  ✗ {p}", file=sys.stderr)
        return 1
    print(f"RELEASE BUNDLE OK  bundleHash={bundle_hash}")
    for label, value in _identity(manifest):
        print(f"  {label} = {value}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
