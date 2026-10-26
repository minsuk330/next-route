from __future__ import annotations

from scripts.capture_fixture import build_job, parse_args
from scripts.check_no_secrets import main as check_main


def test_check_no_secrets_reports_name_not_value(tmp_path, capsys):
    env = tmp_path / "api.env"
    env.write_text(
        "SEOUL_SUBWAY_ARRIVAL=4a6b7c8d9e0f1a2b3c4d\nFLAG=true\nPATH_X=/var/lib/thing/long/path\nSHORT=abc\n",
        encoding="utf-8",
    )
    leaked = tmp_path / "leaked.json"
    leaked.write_text('{"sample": "/api/subway/4a6b7c8d9e0f1a2b3c4d/json"}', encoding="utf-8")
    clean = tmp_path / "clean.json"
    clean.write_text('{"sample": "/api/subway/<redacted>/json"}', encoding="utf-8")

    assert check_main(["--env", str(env), str(clean)]) == 0
    assert check_main(["--env", str(env), str(leaked)]) == 1

    out = capsys.readouterr().out
    assert "FOUND SEOUL_SUBWAY_ARRIVAL" in out
    assert "4a6b7c8d9e0f1a2b3c4d" not in out
    assert "checked 1 env values" in out


def test_capture_builds_synthetic_alert():
    args = parse_args(
        ["--at", "2026-10-23T19:01:00+09:00", "--name", "subway-api-503", "--alertname", "ERROR 로그 급증", "--label", "app=worker"]
    )

    job = build_job(args)

    alert = job.alerts[0]
    assert alert.labels == {"alertname": "ERROR 로그 급증", "app": "worker"}
    assert alert.startsAt.utcoffset().total_seconds() == 9 * 3600
    assert alert.fingerprint == "capture-subway-api-503"
