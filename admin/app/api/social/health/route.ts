import { NextResponse } from 'next/server';
import { ensureSocialSchema, socialDatabase } from '@/lib/social-db';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    const sql = socialDatabase();
    const diagnostic = await sql`
      SELECT current_database() AS database_name,
             current_user AS database_role,
             has_schema_privilege(current_user, 'public', 'USAGE') AS public_usage,
             has_schema_privilege(current_user, 'public', 'CREATE') AS public_create
    `;
    console.info('[tg-social-db-diagnostic]', diagnostic[0]);
    await ensureSocialSchema();
    const rows = await sql`
      SELECT
        (SELECT count(*)::int FROM social_profiles) AS profiles,
        (SELECT count(*)::int FROM social_friendships) AS friendships,
        (SELECT count(*)::int FROM social_messages) AS messages,
        (SELECT count(*)::int FROM social_voice_rooms WHERE is_active = true) AS active_voice_rooms
    `;
    return NextResponse.json({
      success: true,
      service: 'TG Social & Voice',
      database: 'connected',
      stats: rows[0],
    }, { headers: { 'Cache-Control': 'no-store, max-age=0' } });
  } catch (error) {
    console.error('[tg-social-health]', error);
    return NextResponse.json({
      success: false,
      service: 'TG Social & Voice',
      database: 'unavailable',
      error: 'Layanan sosial belum siap dikonfigurasi.',
    }, { status: 503, headers: { 'Cache-Control': 'no-store' } });
  }
}
