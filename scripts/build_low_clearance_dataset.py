#!/usr/bin/env python3
"""Convert an OSM GeoJSON export of height restrictions into a compact TruckNav JSON snapshot."""
import datetime as dt
import json
import math
import sys

src, dst = sys.argv[1], sys.argv[2]
with open(src, "r", encoding="utf-8") as f:
    collection = json.load(f)

features = []
seen = set()
for feature in collection.get("features", []):
    props = feature.get("properties") or {}
    geom = feature.get("geometry") or {}
    coords = geom.get("coordinates")
    if not coords:
        continue

    if geom.get("type") == "Point":
        lon, lat = coords[0], coords[1]
    elif geom.get("type") == "LineString":
        # OSM max-height tags belong on the road segment below the obstruction.
        # Use the middle vertex as a compact map marker; preserve the OSM way ID.
        point = coords[len(coords) // 2]
        lon, lat = point[0], point[1]
    else:
        continue
    if not (math.isfinite(lat) and math.isfinite(lon) and -90 <= lat <= 90 and -180 <= lon <= 180):
        continue

    tags = props.get("tags") if isinstance(props.get("tags"), dict) else props
    clearance = (tags.get("maxheight") or tags.get("maxheight:physical") or
                 tags.get("maxheight:conditional") or "")
    barrier = tags.get("barrier", "")
    if not clearance and barrier != "height_restrictor":
        continue

    osm_id = str(props.get("@id") or props.get("id") or "")
    if not osm_id:
        osm_id = str(tags.get("type", "osm")) + ":" + str(len(features))
    key = (osm_id, round(lat, 6), round(lon, 6), str(clearance))
    if key in seen:
        continue
    seen.add(key)
    features.append({
        "id": osm_id,
        "name": str(tags.get("name") or tags.get("ref") or "Low-clearance restriction"),
        "lat": round(lat, 6),
        "lon": round(lon, 6),
        "height": str(clearance),
        "barrier": str(barrier),
        "road": str(tags.get("highway") or ""),
        "source": "OpenStreetMap"
    })

features.sort(key=lambda x: (x["id"], x["lat"], x["lon"]))
out = {
    "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
    "source": "OpenStreetMap US extract (Geofabrik); ODbL attribution required",
    "count": len(features),
    "features": features
}
with open(dst, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
print(f"Wrote {len(features)} clearance restrictions to {dst}")
