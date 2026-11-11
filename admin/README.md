# TG Launcher Admin Panel

Separate Next.js application deployed on Vercel from the `admin/` root directory.

## Production environment variables

Required for the core admin panel and Neon database:

- `DATABASE_URL` — Neon PostgreSQL connection string (use the pooled connection string for Vercel serverless traffic).
- `ADMIN_EMAIL` — admin login email; this is also the username accepted by the login form.
- `ADMIN_PASSWORD_HASH` — PBKDF2-SHA256 password hash in the format `pbkdf2$<iterations>$<salt-string>$<128-character-hex-digest>`. Use at least 100,000 iterations.
- `ADMIN_SESSION_SECRET` — long, random secret used to sign admin sessions and encrypt stored LiveKit credentials. Keep it stable; rotating it also requires entering the LiveKit API key and secret again in the admin panel.

Required only when the Supabase-backed Social & Voice service is enabled:

- `TG_SOCIAL_SUPABASE_FUNCTION_URL` — URL of the Social Edge Function endpoint.
- `TG_SOCIAL_SUPABASE_PUBLISHABLE_KEY` — publishable key for that Edge Function.
- `TG_SOCIAL_ADMIN_SIGNING_PRIVATE_KEY` — Ed25519 private key used to sign short-lived admin assertions for Social moderation and private-world invitation operations. Store the PEM value with newline handling supported by Vercel environment variables.

## Database architecture

- Neon PostgreSQL is the primary database for admin accounts, member profiles, plans, licenses, app settings, announcements, AI usage, and audit logs.
- Supabase is used separately for the Social & Voice Edge Function service. The Supabase service-role key must not be exposed to the browser or placed in Android builds.
- LiveKit server URL, API key, and API secret are configured in the authenticated admin panel. Credentials are encrypted at rest using `ADMIN_SESSION_SECRET`; the public app configuration endpoint never returns those credentials.

The admin session is HMAC-signed and does not depend on Supabase Auth. Admin API routes validate the session before accessing Neon data. Keep production secrets in Vercel Environment Variables and never commit them to the repository.
