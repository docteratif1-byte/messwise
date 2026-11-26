"""Uploads the signed MessWise bundle named in release.json to a Google Play track (standard library only)."""
import hashlib, json, os, sys, urllib.error, urllib.request

PKG = "com.kodawick.messwise"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PKG}"
TOKEN = os.environ["PLAY_TOKEN"]
rel = json.load(open("release.json", encoding="utf-8"))


def call(method, url, body=None, ctype="application/json"):
    data = body if isinstance(body, (bytes, type(None))) else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": ctype})
    try:
        with urllib.request.urlopen(req, timeout=900) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        print(f"{method} {url.split('?')[0]} -> {e.code}\n{e.read().decode(errors='replace')[:3000]}")
        sys.exit(1)


aab = open(rel["aab"], "rb").read()
digest = hashlib.sha256(aab).hexdigest()
if digest != rel["sha256"]:
    sys.exit(f"bundle checksum {digest} does not match release.json {rel['sha256']}")

edit = call("POST", f"{API}/edits", {})["id"]
print("edit", edit)
bundle = call("POST", f"{UPLOAD}/edits/{edit}/bundles?uploadType=media", aab, "application/octet-stream")
vc = bundle["versionCode"]
print("uploaded versionCode", vc)
if int(vc) != int(rel["version_code"]):
    sys.exit(f"bundle versionCode {vc} is not the expected {rel['version_code']}")

# Release notes only for languages the store listing has (English and/or Urdu).
langs = [l["language"] for l in call("GET", f"{API}/edits/{edit}/listings").get("listings", [])]
notes = [{"language": l, "text": rel["notes"][l.split("-")[0]]} for l in langs if l.split("-")[0] in rel["notes"]]
print("listing languages", langs)

track = rel["track"]
call("PUT", f"{API}/edits/{edit}/tracks/{track}", {
    "track": track,
    "releases": [{"name": rel["name"], "versionCodes": [str(vc)], "status": "completed", "releaseNotes": notes}],
})
done = call("POST", f"{API}/edits/{edit}:commit")
print("committed edit", done.get("id"), "- version", rel["name"], f"({vc}) is on track '{track}' and sent for review")
