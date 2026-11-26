#!/usr/bin/env bash
# Usage: tools/play/release.sh SIGNED.aab VERSION_NAME VERSION_CODE NOTES.json [TRACK]
# NOTES.json: {"en": "...", "ur": "..."} (500 characters at most each). TRACK defaults to alpha
# (Closed testing - Alpha). Pushes a one-commit play-upload branch; GitHub Actions uploads it to Play.
set -euo pipefail
aab=$1; name=$2; code=$3; notes=$4; track=${5:-alpha}
here=$(cd "$(dirname "$0")" && pwd); repo=$(cd "$here/../.." && pwd)
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
mkdir -p "$work/.github/workflows"
cp "$here/play-upload.yml" "$work/.github/workflows/play-upload.yml"
cp "$here/play_upload.py" "$work/"
cp "$aab" "$work/MessWise-$name.aab"
python3 - "$work" "$name" "$code" "$notes" "$track" <<'PY'
import hashlib, json, sys
work, name, code, notes, track = sys.argv[1:]
n = json.load(open(notes, encoding="utf-8"))
for k, v in n.items():
    assert len(v) <= 500, f"{k} release notes are {len(v)} characters (500 at most)"
aab = f"MessWise-{name}.aab"
json.dump({"aab": aab, "name": name, "version_code": int(code), "track": track, "notes": n,
           "sha256": hashlib.sha256(open(f"{work}/{aab}", "rb").read()).hexdigest()},
          open(f"{work}/release.json", "w", encoding="utf-8"), ensure_ascii=False, indent=2)
PY
cd "$work"
git init -q -b play-upload
git -c user.name="KodaWick release" -c user.email="kodawick4+messwise@gmail.com" add -A
git -c user.name="KodaWick release" -c user.email="kodawick4+messwise@gmail.com" commit -q -m "MessWise $name ($code) to $track"
git push -q -f "$(git -C "$repo" remote get-url origin)" HEAD:play-upload
echo "pushed MessWise $name ($code) for track $track"
