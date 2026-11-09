import { neon } from '@neondatabase/serverless';
import { database, ensureSchema } from '@/lib/db';

let schemaReady: Promise<void> | null = null;

export function socialDatabase() {
  const url = process.env.TG_SOCIAL_DATABASE_URL;
  if (!url) throw new Error('TG_SOCIAL_DATABASE_URL belum dikonfigurasi.');
  return neon(url);
}

export async function isSocialFeatureEnabled(feature: 'friendsEnabled' | 'voiceEnabled' | 'livekitEnabled' | 'uploadsEnabled', fallback = true) {
  try {
    await ensureSchema();
    const rows = await database()`SELECT value FROM app_settings WHERE key='social' LIMIT 1`;
    const settings = rows[0]?.value as Record<string, unknown> | undefined;
    return typeof settings?.[feature] === 'boolean' ? settings[feature] as boolean : fallback;
  } catch (error) {
    console.error('[tg-social-settings]', error);
    return fallback;
  }
}

let schemaReady: Promise<void> | null = null;

export function socialDatabase() {
  const url = process.env.TG_SOCIAL_DATABASE_URL;
  if (!url) throw new Error('TG_SOCIAL_DATABASE_URL belum dikonfigurasi.');
  return neon(url);
}

export async function ensureSocialSchema() {
  if (!schemaReady) schemaReady = (async () => {
    const sql = socialDatabase();
    await sql`CREATE TABLE IF NOT EXISTS social_profiles (
      user_id text PRIMARY KEY, username text NOT NULL, username_lower text NOT NULL,
      avatar_url text NOT NULL DEFAULT '', bio text NOT NULL DEFAULT '',
      platform text NOT NULL DEFAULT 'android',
      presence text NOT NULL DEFAULT 'offline' CHECK (presence IN ('online','offline','in_game','away')),
      last_seen_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
      updated_at timestamptz NOT NULL DEFAULT now()
    )`;
    await sql`CREATE UNIQUE INDEX IF NOT EXISTS social_profiles_username_lower_idx ON social_profiles(username_lower)`;
    await sql`CREATE TABLE IF NOT EXISTS social_friendships (
      id bigserial PRIMARY KEY, requester_id text NOT NULL, addressee_id text NOT NULL,
      status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','accepted','blocked')),
      created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
      CHECK (requester_id <> addressee_id), UNIQUE(requester_id, addressee_id)
    )`;
    await sql`CREATE INDEX IF NOT EXISTS social_friendships_addressee_idx ON social_friendships(addressee_id,status)`;
    await sql`CREATE TABLE IF NOT EXISTS social_conversations (
      id text PRIMARY KEY, conversation_type text NOT NULL DEFAULT 'direct'
      CHECK (conversation_type IN ('direct','group','room')), title text NOT NULL DEFAULT '',
      created_by text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
    )`;
    await sql`CREATE TABLE IF NOT EXISTS social_conversation_members (
      conversation_id text NOT NULL REFERENCES social_conversations(id) ON DELETE CASCADE,
      user_id text NOT NULL, joined_at timestamptz NOT NULL DEFAULT now(),
      last_read_at timestamptz, PRIMARY KEY(conversation_id,user_id)
    )`;
    await sql`CREATE TABLE IF NOT EXISTS social_messages (
      id text PRIMARY KEY, conversation_id text NOT NULL REFERENCES social_conversations(id) ON DELETE CASCADE,
      sender_id text NOT NULL, body text NOT NULL DEFAULT '', attachment_url text NOT NULL DEFAULT '',
      created_at timestamptz NOT NULL DEFAULT now(), edited_at timestamptz, deleted_at timestamptz
    )`;
    await sql`CREATE INDEX IF NOT EXISTS social_messages_conversation_created_idx ON social_messages(conversation_id,created_at DESC)`;
    await sql`CREATE TABLE IF NOT EXISTS social_voice_rooms (
      id text PRIMARY KEY, room_name text NOT NULL, created_by text NOT NULL,
      livekit_room_name text NOT NULL UNIQUE,
      visibility text NOT NULL DEFAULT 'friends' CHECK (visibility IN ('friends','public','private')),
      max_participants integer NOT NULL DEFAULT 16, is_active boolean NOT NULL DEFAULT true,
      created_at timestamptz NOT NULL DEFAULT now(), closed_at timestamptz
    )`;
    await sql`CREATE TABLE IF NOT EXISTS social_reports (
      id bigserial PRIMARY KEY, reporter_id text NOT NULL, target_user_id text NOT NULL,
      reason text NOT NULL, details text NOT NULL DEFAULT '',
      status text NOT NULL DEFAULT 'open' CHECK (status IN ('open','reviewing','resolved','dismissed')),
      created_at timestamptz NOT NULL DEFAULT now()
    )`;
  })().catch(error => { schemaReady = null; throw error; });
  return schemaReady;
}
