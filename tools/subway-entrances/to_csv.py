"""geojsonseq(osmium export) → entrances.csv. 출구 번호(ref)가 없는 출입구는 버린다 — 번호를 보여 주는 게 목적이다."""
import csv
import hashlib
import json
import os
import sys


def main(geojsonseq, pbf):
    rows = []
    with open(geojsonseq, encoding="utf-8") as f:
        for line in f:
            line = line.strip().lstrip("\x1e")
            if not line:
                continue
            feat = json.loads(line)
            props = feat.get("properties") or {}
            ref = (props.get("ref") or "").strip()
            if not ref:
                continue
            lon, lat = feat["geometry"]["coordinates"][:2]
            label = (props.get("name") or props.get("description") or "").strip()
            osm_id = str(feat.get("id") or props.get("@id") or "")
            rows.append((osm_id, round(lat, 7), round(lon, 7), ref, label))
    rows.sort(key=lambda r: (r[1], r[2]))
    with open(pbf, "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()[:16]
    out = csv.writer(sys.stdout, lineterminator="\n")
    sys.stdout.write(f"# source={os.path.basename(pbf)} sha256={digest} license=ODbL(c)OpenStreetMap contributors\n")
    out.writerow(["osm_id", "lat", "lon", "ref", "label"])
    out.writerows(rows)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
