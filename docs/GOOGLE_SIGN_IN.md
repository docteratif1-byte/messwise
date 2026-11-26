# Google sign-in setup (one time)

The app reads the Google client ID from the server (`messwise.settings.google_web_client_id`), so no app update is needed after this.

## 1. Google Cloud (signed in as Kodawick4@gmail.com)
1. Open the Google Cloud project used for KodaWick apps (or create **MessWise**).
2. Google Auth Platform → Branding: app name **MessWise**, support email Kodawick4@gmail.com, scopes email, profile, openid only. Audience → Publish app.
3. Clients → Create client → **Web application** "MessWise server". Copy the Client ID and secret.
4. Create client → **Android**:
   - `com.kodawick.messwise`, SHA-1 `EA:81:10:A9:2D:F5:70:1C:97:B6:0D:E4:0E:8B:05:2B:4B:72:E5:A9` (upload key)
   - `com.kodawick.messwise.test`, SHA-1 `C1:3F:F6:3F:E0:E8:E7:C6:0C:4A:0B:E9:2B:E2:58:6B:82:A8:B7:2C` (test APK)
   - after the first Play upload: `com.kodawick.messwise`, SHA-1 of the **App signing key**

## 2. Supabase (KodaWick → messwise project)
Authentication → Sign In / Providers → Google: add the MessWise Web Client ID to **Client IDs** (comma-separated, after Comitto's). Keep the existing secret.

## 3. Tell the app
```sql
update messwise.settings set value = to_jsonb('PASTE-WEB-CLIENT-ID.apps.googleusercontent.com'::text) where key = 'google_web_client_id';
```
