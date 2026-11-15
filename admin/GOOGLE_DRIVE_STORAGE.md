# Google Drive storage migration

This branch introduces the server-side Google Drive storage foundation. It is intentionally additive: Neon remains the active system of record until the Drive connection, data migration, and application cutover have been verified.

## Google Cloud setup

1. Create/select a Google Cloud project and enable the Google Drive API.
2. Configure an OAuth consent screen for the account that owns the TG Launcher Drive folder.
3. Create an OAuth 2.0 Web application client.
4. Add this authorized redirect URI: `https://tg-launcher-admin-sigma.vercel.app/api/admin/storage/drive/callback` (or the exact production admin domain you use).
5. Grant the backend the `drive.file` scope. The backend creates a visible `TG Launcher` folder and its collection folders in the connected user's My Drive.

## Vercel environment variables

Set these only in the Vercel project `tg-launcher-admin` (Production and Preview as appropriate):

- `GOOGLE_DRIVE_CLIENT_ID`
- `GOOGLE_DRIVE_CLIENT_SECRET`
- `GOOGLE_DRIVE_REFRESH_TOKEN` (obtained once from the authenticated `/api/admin/storage/drive/connect` flow; the callback displays it only to the logged-in admin)
- Optional: `GOOGLE_DRIVE_ROOT_FOLDER_ID` if an existing folder should be used instead of creating `TG Launcher`.

Never put these credentials in the Android APK, browser JavaScript, Git, or chat.

## Folder layout

```text
TG Launcher/
  Users/
  Profiles/
  Settings/
  Licenses/
  License Keys/
  Plans/
  Announcements/
  AI Usage/
  Audit Logs/
  Backups/
  Crash Logs/
  Uploads/
```

The adapter stores JSON records as separate files in each collection folder. The filenames are deterministic hashes of record IDs, so user email addresses and other identifiers are not exposed in Drive filenames.

## Current migration status

- [x] Isolated work branch created from the latest TG Launcher feature branch.
- [x] Drive REST/OAuth refresh-token client and folder initializer added.
- [x] Admin-only connection status and folder initialization endpoints added.
- [x] Admin-only OAuth connect/callback flow added; callback returns the refresh token only to the authenticated admin for manual Vercel configuration.
- [ ] Google OAuth credentials and refresh token configured in Vercel.
- [ ] Export existing Neon records and import/verify them in Drive.
- [ ] Convert every admin and launcher data route to the Drive repository.
- [ ] Verify login, registration, plans, licenses, settings, announcements, audit logs, AI usage, and Social & Voice dependencies.
- [ ] Switch the active data provider only after parity checks and rollback backup.

## Important consistency limits

Google Drive is file storage, not a transactional database. It does not provide PostgreSQL transactions, uniqueness constraints, or relational joins. This adapter is a migration foundation, not permission to switch production traffic yet. Before cutover, write paths need conflict-safe handling and the application needs bounded caching/retry behavior. High-contention data such as OTP verification, sessions, rate limits, and license activation may need a small transactional service even if bulk records and backups live in Drive.
