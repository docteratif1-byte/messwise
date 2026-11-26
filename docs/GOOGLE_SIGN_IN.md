# Google sign-in setup (done 26 Nov 2026)

The app reads the Google client ID from the server (`messwise.settings.google_web_client_id`), so no app update is needed when clients change.

## 1. Google Cloud: project **MessWise** (`messwise-515915`)
- Google Auth Platform → Branding: app name **MessWise**, home page and privacy policy on messwise-r9qh.onrender.com, authorised domain `messwise-r9qh.onrender.com`, developer contact Kodawick4@gmail.com. Scopes: email, profile, openid only. Published to production.
- Clients:

| Type | Name | Package | SHA-1 | Client ID |
|---|---|---|---|---|
| Web | MessWise server | — | — | `593038022726-jh59tbrcrgu7b7i509hec2hml6sv42tp.apps.googleusercontent.com` |
| Android | MessWise Android (Play) | `com.kodawick.messwise` | `EA:81:10:A9:2D:F5:70:1C:97:B6:0D:E4:0E:8B:05:2B:4B:72:E5:A9` (upload key) | `593038022726-p63vjclqp0ulj5pahisi7ce56htjmc0g` |
| Android | MessWise Android (Play signing key) | `com.kodawick.messwise` | `E8:F1:78:3B:69:A5:20:20:D0:03:60:E1:35:CE:BD:8E:B8:05:0E:EF` (Play app signing key) | `593038022726-kktk…` |
| Android | MessWise Android (Play earlier key) | `com.kodawick.messwise` | `38:7B:31:70:13:6D:F6:31:CC:5B:35:70:9A:EE:27:A3:CB:07:0C:12` (Play earlier signing key) | `593038022726-pr41…` |
| Android | MessWise Android (test build) | `com.kodawick.messwise.test` | `C1:3F:F6:3F:E0:E8:E7:C6:0C:4A:0B:E9:2B:E2:58:6B:82:A8:B7:2C` (debug key in repo) | `593038022726-geaa2b7dlsaj0aq5o6cdoelvkdkcfsqs` |

Android client IDs end in `.apps.googleusercontent.com`. The Play signing fingerprints come from Play Console → Protected with Play → App signing.

## 2. Supabase (KodaWick → messwise, `nqyljmrcnbwkwxmgjnzj`)
Authentication → Sign In / Providers → Google: enabled with the Web client ID above in **Client IDs**. No secret is needed for ID-token sign-in from the app.

## 3. App setting
```sql
update messwise.settings set value = to_jsonb('593038022726-jh59tbrcrgu7b7i509hec2hml6sv42tp.apps.googleusercontent.com'::text) where key = 'google_web_client_id';
```
