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

export async function ensureSocialSchema() {
  if (!schemaReady) schemaReady = (async () => {
    const sql = socialDatabase();
    await sql`SELECT 1`;
  })().catch(error => { schemaReady = null; throw error; });
  return schemaReady;
}
