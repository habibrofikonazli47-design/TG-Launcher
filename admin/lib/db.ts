import { neon } from '@neondatabase/serverless';

let schemaReady: Promise<void> | null = null;
export function database() {
  const url = process.env.DATABASE_URL;
  if (!url) throw new Error('DATABASE_URL belum tersedia. Pastikan integrasi Neon terpasang pada project Vercel.');
  return neon(url);
}
export async function ensureSchema() {
  if (!schemaReady) schemaReady = (async () => {
    const sql = database();
    await sql`CREATE TABLE IF NOT EXISTS profiles (
      id text PRIMARY KEY, email text UNIQUE, display_name text NOT NULL DEFAULT '',
      role text NOT NULL DEFAULT 'user', plan text NOT NULL DEFAULT 'free',
      status text NOT NULL DEFAULT 'active', expires_at timestamptz,
      created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
    )`;
    await sql`CREATE TABLE IF NOT EXISTS licenses (
      id text PRIMARY KEY, user_id text, email text, plan text NOT NULL DEFAULT 'free',
      status text NOT NULL DEFAULT 'active', expires_at timestamptz,
      created_at timestamptz NOT NULL DEFAULT now()
    )`;
    await sql`CREATE TABLE IF NOT EXISTS plans (
      code text PRIMARY KEY, name text NOT NULL, features jsonb NOT NULL DEFAULT '[]'::jsonb,
      limits jsonb NOT NULL DEFAULT '{}'::jsonb, updated_at timestamptz NOT NULL DEFAULT now()
    )`;
    await sql`CREATE TABLE IF NOT EXISTS launcher_ai_usage (
      id bigserial PRIMARY KEY, user_id text, request_count integer NOT NULL DEFAULT 1,
      created_at timestamptz NOT NULL DEFAULT now()
    )`;
    await sql`CREATE TABLE IF NOT EXISTS app_settings (
      key text PRIMARY KEY, value jsonb NOT NULL DEFAULT '{}'::jsonb,
      updated_at timestamptz NOT NULL DEFAULT now(), updated_by text
    )`;
    await sql`CREATE TABLE IF NOT EXISTS announcements (
      id text PRIMARY KEY, title text NOT NULL DEFAULT '', content text NOT NULL DEFAULT '',
      image_url text NOT NULL DEFAULT '', link_url text NOT NULL DEFAULT '',
      enabled boolean NOT NULL DEFAULT false, target_platform text NOT NULL DEFAULT 'all',
      starts_at timestamptz, ends_at timestamptz, updated_at timestamptz NOT NULL DEFAULT now()
    )`;
    await sql`CREATE TABLE IF NOT EXISTS license_keys (
      id text PRIMARY KEY, license_key text UNIQUE NOT NULL, plan text NOT NULL DEFAULT 'premium',
      status text NOT NULL DEFAULT 'unused', max_uses integer NOT NULL DEFAULT 1,
      used_count integer NOT NULL DEFAULT 0, expires_at timestamptz,
      created_at timestamptz NOT NULL DEFAULT now()
    )`;
    await sql`ALTER TABLE profiles ADD COLUMN IF NOT EXISTS license_id text`;\n    await sql`ALTER TABLE license_keys ADD COLUMN IF NOT EXISTS duration_days integer`;
    await sql`ALTER TABLE license_keys ADD COLUMN IF NOT EXISTS max_devices integer NOT NULL DEFAULT 1`;
    await sql`ALTER TABLE license_keys ADD COLUMN IF NOT EXISTS user_id text`;
    await sql`ALTER TABLE license_keys ADD COLUMN IF NOT EXISTS activated_at timestamptz`;
    await sql`ALTER TABLE license_keys ADD COLUMN IF NOT EXISTS tier text NOT NULL DEFAULT 'premium'`;
    await sql`CREATE TABLE IF NOT EXISTS admin_audit_logs (
      id bigserial PRIMARY KEY, actor text NOT NULL DEFAULT 'admin', action text NOT NULL,
      detail jsonb NOT NULL DEFAULT '{}'::jsonb, created_at timestamptz NOT NULL DEFAULT now()
    )`;
    await sql`CREATE INDEX IF NOT EXISTS profiles_created_at_idx ON profiles(created_at DESC)`;
    await sql`CREATE INDEX IF NOT EXISTS licenses_status_idx ON licenses(status)`;
    // Paket bawaan mengikuti fitur Premium/Free yang benar-benar tercantum di NuxPremiumDialog.kt.
    // Migrasi hanya menyentuh seed lama yang masih persis default; perubahan admin tidak ditimpa.
    await sql`INSERT INTO plans(code,name,features,limits) VALUES
      ('free','Free','["base_launcher"]'::jsonb,'{"instances":3,"accounts":2,"ai_daily":5}'::jsonb),
      ('premium','Premium','["base_launcher","screen_recorder","voice_rooms","unlimited_instances","custom_video_hero","premium_badge","snapshot_beta_versions","unlimited_accounts","turbo_downloader"]'::jsonb,'{"instances":-1,"accounts":-1,"ai_daily":-1,"download_workers":48}'::jsonb)
      ON CONFLICT (code) DO NOTHING`;
    await sql`UPDATE plans SET
      features='["base_launcher"]'::jsonb,
      limits='{"instances":3,"accounts":2,"ai_daily":5}'::jsonb,
      updated_at=now()
      WHERE code='free'
      AND features='["Launcher dasar","Manajemen versi"]'::jsonb
      AND limits='{"ai_requests":10}'::jsonb`;
    await sql`UPDATE plans SET
      features='["base_launcher","screen_recorder","voice_rooms","unlimited_instances","custom_video_hero","premium_badge","snapshot_beta_versions","unlimited_accounts","turbo_downloader"]'::jsonb,
      limits='{"instances":-1,"accounts":-1,"ai_daily":-1,"download_workers":48}'::jsonb,
      updated_at=now()
      WHERE code='premium'
      AND features='["Semua fitur Free","Fitur Premium"]'::jsonb
      AND limits='{"ai_requests":1000}'::jsonb`;
  })().catch(err => { schemaReady = null; throw err; });
  return schemaReady;
}
