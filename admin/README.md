# TG Launcher Admin Panel

Separate Next.js application deployed on Vercel from the `admin/` root directory.

Required Vercel Production environment variables:
- `ADMIN_EMAIL`
- `ADMIN_PASSWORD_HASH` (`scrypt$<salt-hex>$<hash-hex>`)
- `ADMIN_SESSION_SECRET`
- `SUPABASE_URL`
- `SUPABASE_SERVICE_ROLE_KEY` (server-only; never expose to browser)
- `VERCEL_API_TOKEN` (encrypted; least-privilege token able to edit this project's env vars)
- `VERCEL_PROJECT_ID`
- `VERCEL_TEAM_ID`

The admin session is signed and does not use Supabase Auth, so login remains available during database outages. Database management changes Vercel encrypted environment variables; redeploy after changing them.
