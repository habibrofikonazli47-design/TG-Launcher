import { NextRequest, NextResponse } from 'next/server';
import { database, ensureSchema } from '@/lib/db';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';
const FIREBASE_WEB_API_KEY = 'AIzaSyDIqvaTHjuwNboBXcVDoY1JYJntS0ECsJ0';

async function verifyToken(token: string): Promise<{ uid: string; email: string } | null> {
  try {
    const response = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${FIREBASE_WEB_API_KEY}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ idToken: token }),
      cache: 'no-store',
    });
    if (!response.ok) return null;
    const user = (await response.json())?.users?.[0];
    return user?.localId && user?.email ? { uid: String(user.localId), email: String(user.email).toLowerCase() } : null;
  } catch { return null; }
}

async function getQuota(uid: string, consume: boolean) {
  await ensureSchema();
  const sql = database();
  const profiles = await sql`SELECT plan,status,expires_at FROM profiles WHERE id=${uid} LIMIT 1`;
  const profile = profiles[0];
  const expiry = profile?.expires_at ? new Date(profile.expires_at) : null;
  const premium = profile?.plan === 'premium' && profile?.status === 'active' && (!expiry || expiry.getTime() > Date.now());
  const code = premium ? 'premium' : 'free';
  const planRows = await sql`SELECT limits FROM plans WHERE code=${code} LIMIT 1`;
  const limits = planRows[0]?.limits || {};
  const aiSettingsRows = await sql`SELECT value FROM app_settings WHERE key='ai' LIMIT 1`;
  const aiSettings = aiSettingsRows[0]?.value || {};
  const configuredDailyLimit = premium ? aiSettings.premiumDailyQuota : aiSettings.freeDailyQuota;
  const dailyLimit = Number.isInteger(configuredDailyLimit)
    ? configuredDailyLimit
    : (typeof limits.ai_daily === 'number' ? limits.ai_daily : (premium ? -1 : 5));
  const usage = await sql`SELECT COALESCE(SUM(request_count),0)::int AS used_count FROM launcher_ai_usage WHERE user_id=${uid} AND created_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Jakarta') AT TIME ZONE 'Asia/Jakarta'`;
  let usedCount = Number(usage[0]?.used_count || 0);
  const isUnlimited = dailyLimit < 0;
  const allowed = isUnlimited || usedCount < dailyLimit;
  if (consume && allowed) {
    await sql`INSERT INTO launcher_ai_usage(user_id,request_count) VALUES(${uid},1)`;
    usedCount += 1;
  }
  const remainingQuota = isUnlimited ? -1 : Math.max(0, dailyLimit - usedCount);
  return { success: true, date: new Date(Date.now() + 7 * 60 * 60 * 1000).toISOString().slice(0, 10), usedCount, remainingQuota, dailyLimit, isUnlimited, allowed };
}

async function authorized(req: NextRequest) {
  const header = req.headers.get('authorization') || '';
  const token = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
  if (!token) return null;
  return verifyToken(token);
}

export async function GET(req: NextRequest) {
  const identity = await authorized(req);
  if (!identity) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  const requestedUid = req.nextUrl.searchParams.get('uid');
  if (requestedUid && requestedUid !== identity.uid) return NextResponse.json({ error: 'UID tidak cocok dengan token' }, { status: 403 });
  try {
    const result = await getQuota(identity.uid, false);
    return NextResponse.json(result, { headers: { 'Cache-Control': 'no-store, private' } });
  } catch (error) {
    console.error('[ai-quota:get]', error);
    return NextResponse.json({ error: 'Gagal membaca kuota AI' }, { status: 503 });
  }
}

export async function POST(req: NextRequest) {
  const identity = await authorized(req);
  if (!identity) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  try {
    const body = await req.json().catch(() => ({}));
    if (body.uid && body.uid !== identity.uid) return NextResponse.json({ error: 'UID tidak cocok dengan token' }, { status: 403 });
    const result = await getQuota(identity.uid, true);
    return NextResponse.json(result, { headers: { 'Cache-Control': 'no-store, private' } });
  } catch (error) {
    console.error('[ai-quota:post]', error);
    return NextResponse.json({ error: 'Gagal memperbarui kuota AI' }, { status: 503 });
  }
}
