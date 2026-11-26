# MessWise — build, signing and release

## Build outputs
Every push to `main` runs `.github/workflows/ci.yml` and publishes to the `messwise-builds` branch:
`Messwise-test.apk` (package `com.kodawick.messwise.test`, debug-signed, with the email test sign-in), `Messwise-release-unsigned.aab`, `Messwise-release-unsigned.apk`, test reports and `errors.txt`.

## Signing (off GitHub)
Upload key `messwise-upload.jks`, alias `messwise`, CN=KodaWick, O=KodaWick, C=PK, RSA 4096, valid 10,000 days. Kept with KodaWick, never in this repo.

- SHA-1: `EA:81:10:A9:2D:F5:70:1C:97:B6:0D:E4:0E:8B:05:2B:4B:72:E5:A9`
- SHA-256: `C3:C4:53:97:F1:B9:D1:A4:BC:99:8C:9C:87:B2:8E:89:C7:D7:1B:0D:2F:41:3F:85:CA:18:04:9B:E2:B0:6A:65`

```bash
jarsigner -keystore messwise-upload.jks -sigalg SHA256withRSA -digestalg SHA-256 \
  -signedjar Messwise-1.0.0-release.aab Messwise-release-unsigned.aab messwise
jarsigner -verify Messwise-1.0.0-release.aab
```
Play App Signing is on. App signing key SHA-1 `E8:F1:78:3B:69:A5:20:20:D0:03:60:E1:35:CE:BD:8E:B8:05:0E:EF` (earlier key `38:7B:31:70:13:6D:F6:31:CC:5B:35:70:9A:EE:27:A3:CB:07:0C:12`); both are Android OAuth clients (GOOGLE_SIGN_IN.md).

## Status (27 Nov 2026)
- 1.0.1 (2) and then 1.0.2 (3) uploaded to Closed testing - Alpha automatically from GitHub (see Updating) and sent for review.
- Play app **MessWise: Mess & Tea Bills** (app id 4973624961961626003) created under the KodaWick developer account.
- Release 1 (1.0.0) on **Closed testing - Alpha**, 178 countries, email list "MessWise testers" (24), feedback kodawick4+messwise@gmail.com. Sent for review with the store listing and all App content declarations.
- Upload bundle: `MessWise-1.0.0.aab` with the R8 mapping removed (Chrome upload limit). Upload `mapping-1.0.0.txt` later from App bundle explorer for readable crash reports.
- Production needs 12+ testers opted in for 14 days, then Apply for production.

Debug / test key (in the repo, `app/debug.keystore`): SHA-1 `C1:3F:F6:3F:E0:E8:E7:C6:0C:4A:0B:E9:2B:E2:58:6B:82:A8:B7:2C`.

## Backend
Supabase free plan: organisation **KodaWick**, project **messwise** (`nqyljmrcnbwkwxmgjnzj`, Tokyo), owned by docter.atif1@gmail.com (sign in with GitHub). Private schema `messwise`. The app uses the project's publishable key. The app calls only `public.mw_*` functions (SECURITY DEFINER, row-level security on every table, no table grants). Photos go to the private `messwise` storage bucket (3 MB, images and PDF).
Migrations: `supabase/migrations/0001` to `0006`; `supabase/live/messwise_full_setup.sql` is all of them in one file (0001-0004 run on 26 Nov 2026; 0005 who-sees-what and 0006 Canteen Manager money rules + party non-members run on 27 Nov 2026 from `supabase/live/messwise_000N_*.sql`). Local tests: `supabase/tests/reset.sh && python3 supabase/tests/run_tests.py` (172 checks).

## Closed test (new personal Play account)
At least 12 testers opted in for 14 days in a row before production access can be requested. The tester list is kept privately, not in this repo.

## Updating
1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. Texts: edit `tools/strings.py`, run `python3 tools/gen_strings.py`.
3. Push, download from `messwise-builds`, sign with the upload key (off GitHub).
4. Write release notes in `tools/play/notes/VERSION.json` (`en`, `ur`; 500 characters each at most), then
   `tools/play/release.sh SIGNED.aab VERSION_NAME VERSION_CODE tools/play/notes/VERSION.json [alpha]`.
   It pushes a one-commit `play-upload` branch; its workflow uploads the bundle to the track and sends it for review.

Automatic upload needs no key or password: GitHub Actions signs in to Google through Workload Identity Federation
(Google Cloud project messwise-515915, pool `github`, provider `github-actions`, only repository
docteratif1-byte/messwise) as `messwise-play-upload@messwise-515915.iam.gserviceaccount.com`. In Play Console that
service account may only view MessWise and release it to testing tracks; production releases stay with the owner.
