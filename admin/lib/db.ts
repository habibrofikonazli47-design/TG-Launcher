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
    await sql`CREATE TABLE IF NOT EXISTS admin_audit_logs (
      id bigserial PRIMARY KEY, actor text NOT NULL DEFAULT 'admin', action text NOT NULL,
      detail jsonb NOT NULL DEFAULT '{}'::jsonb, created_at timestamptz NOT NULL DEFAULT now()
    )`;
    await sql`CREATE INDEX IF NOT EXISTS profiles_created_at_idx ON profiles(created_at DESC)`;
    await sql`CREATE INDEX IF NOT EXISTS licenses_status_idx ON licenses(status)`;
    await sql`INSERT INTO plans(code,name,features,limits) VALUES
      ('free','Free','["Launcher dasar","Manajemen versi"]'::jsonb,'{"ai_requests":10}'::jsonb),
      ('premium','Premium','["Semua fitur Free","Fitur Premium"]'::jsonb,'{"ai_requests":1000}'::jsonb)
      ON CONFLICT (code) DO NOTHING`;
  })().catch(err => { schemaReady = null; throw err; });
  return schemaReady;
}
