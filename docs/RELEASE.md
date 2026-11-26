# Messwise — build, signing and release

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
Use Play App Signing (default). After the first upload, add the **App signing key SHA-1** from Play Console → Test and release → App integrity to the Android OAuth client (GOOGLE_SIGN_IN.md).

Debug / test key (in the repo, `app/debug.keystore`): SHA-1 `C1:3F:F6:3F:E0:E8:E7:C6:0C:4A:0B:E9:2B:E2:58:6B:82:A8:B7:2C`.

## Backend
Supabase free plan, inside the existing project `mvgezwubvnmftnmkzgts`, private schema `messwise`. The app calls only `public.mw_*` functions (SECURITY DEFINER, row-level security on every table, no table grants). Photos go to the private `messwise` storage bucket (3 MB, images and PDF).
Migrations: `supabase/migrations/0001` to `0004`. Local tests: `supabase/tests/reset.sh && python3 supabase/tests/run_tests.py` (122 checks).

## Closed test (new personal Play account)
At least 12 testers opted in for 14 days in a row before production access can be requested. The tester list is kept privately, not in this repo.

## Updating
1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. Texts: edit `tools/strings.py`, run `python3 tools/gen_strings.py`.
3. Push, download from `messwise-builds`, sign, upload to Play Console.
