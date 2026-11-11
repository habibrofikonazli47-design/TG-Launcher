import { database, ensureSchema } from '@/lib/db';

export async function isSocialFeatureEnabled(
  feature: 'friendsEnabled' | 'voiceEnabled' | 'livekitEnabled' | 'uploadsEnabled' | 'worldInvitesEnabled',
  fallback = true,
) {
  try {
    // Feature switches remain in the primary TG database so the Admin Panel
    // can centrally enable/disable Social without coupling Social data storage.
    await ensureSchema();
    const rows = await database()`SELECT value FROM app_settings WHERE key='social' LIMIT 1`;
    const settings = rows[0]?.value as Record<string, unknown> | undefined;
    return typeof settings?.[feature] === 'boolean' ? settings[feature] as boolean : fallback;
  } catch (error) {
    console.error('[tg-social-settings]', error);
    return fallback;
  }
}
