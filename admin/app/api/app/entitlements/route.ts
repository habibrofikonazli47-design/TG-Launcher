import { NextRequest, NextResponse } from 'next/server';
import { database, ensureSchema } from '@/lib/db';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

const FIREBASE_WEB_API_KEY = 'AIzaSyDIqvaTHjuwNboBXcVDoY1JYJntS0ECsJ0';

type FirebaseUser = { localId: string; email?: string; displayName?: string };

async function verifyFirebaseIdToken(idToken: string): Promise<FirebaseUser | null> {
  const response = await fetch(
    `https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${FIREBASE_WEB_API_KEY}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ idToken }),
      cache: 'no-store',
    },
  );
  if (!response.ok) return null;
  const payload = await response.json();
  const user = payload?.users?.[0];
  if (!user?.localId) return null;
  return { localId: String(user.localId), email: user.email ? String(user.email).toLowerCase() : undefined, displayName: user.displayName ? String(user.displayName) : undefined };
}

export async function GET(req: NextRequest) {
  const authorization = req.headers.get('authorization') || '';
  const token = authorization.startsWith('Bearer ') ? authorization.slice(7).trim() : '';
  if (!token) return NextResponse.json({ error: 'Bearer token diperlukan' }, { status: 401 });

  try {
    const identity = await verifyFirebaseIdToken(token);
    if (!identity) return NextResponse.json({ error: 'Token akun tidak valid atau sudah kedaluwarsa' }, { status: 401 });
    if (!identity.email) return NextResponse.json({ error: 'Akun tidak memiliki alamat email' }, { status: 400 });

    await ensureSchema();
    const sql = database();
    const policyRows = await sql`SELECT key,value FROM app_settings WHERE key IN ('updates','general')`;
    const settings = Object.fromEntries(policyRows.map((row: any) => [row.key, row.value || {}]));
    const updates = settings.updates || {};
    const general = settings.general || {};
    const forceUpdate = updates.forceUpdate === true || general.forceUpdate === true;
    const minimumVersion = String(
      updates.forceUpdate === true ? updates.minimumSupportedVersion || '1.0.0'
        : general.forceUpdate === true ? general.minimumAppVersion || updates.minimumSupportedVersion || '1.0.0'
          : updates.minimumSupportedVersion || general.minimumAppVersion || '1.0.0',
    ).replace(/^v/, '');
    const clientVersion = String(req.headers.get('x-tg-app-version') || '').trim().replace(/^v/, '');
    const compareVersion = (a: string, b: string) => {
      const partsA = a.split(/[.-]/).map(part => Number(part.replace(/\D/g, '')) || 0);
      const partsB = b.split(/[.-]/).map(part => Number(part.replace(/\D/g, '')) || 0);
      for (let i = 0; i < Math.max(partsA.length, partsB.length); i++) {
        const diff = (partsA[i] || 0) - (partsB[i] || 0);
        if (diff !== 0) return diff;
      }
      return 0;
    };
    if (forceUpdate && (!clientVersion || compareVersion(clientVersion, minimumVersion) < 0)) {
      return NextResponse.json({
        error: 'Versi TG Launcher ini sudah tidak didukung. Silakan perbarui aplikasi.',
        code: 'UPDATE_REQUIRED',
        minimumSupportedVersion: minimumVersion,
      }, { status: 426, headers: { 'Cache-Control': 'no-store' } });
    }
    // Reconcile legacy profiles created once by Firebase UID and once by email.
    // The Firebase-verified email is authoritative for merging a previously assigned Premium plan.
    const matchingProfiles = await sql`SELECT id,email,display_name,plan,status,expires_at,feature_overrides,limit_overrides FROM profiles WHERE id=${identity.localId} OR lower(email)=${identity.email} ORDER BY CASE WHEN id=${identity.localId} THEN 0 ELSE 1 END`;
    const uidProfile = matchingProfiles.find((row: any) => row.id === identity.localId);
    const emailProfile = matchingProfiles.find((row: any) => String(row.email || '').toLowerCase() === identity.email);
    if (!uidProfile && emailProfile) {
      await sql`UPDATE profiles SET id=${identity.localId},email=${identity.email},display_name=CASE WHEN display_name='' THEN ${identity.displayName || identity.email.split('@')[0]} ELSE display_name END,updated_at=now() WHERE id=${emailProfile.id}`;
    } else if (!uidProfile && !emailProfile) {
      await sql`INSERT INTO profiles(id,email,display_name,plan,status) VALUES(${identity.localId},${identity.email},${identity.displayName || identity.email.split('@')[0]},'free','active') ON CONFLICT (id) DO NOTHING`;
    } else if (uidProfile && emailProfile && uidProfile.id !== emailProfile.id) {
      const emailExpiry = emailProfile.expires_at ? new Date(emailProfile.expires_at).getTime() : null;
      const emailPremiumActive = emailProfile.plan === 'premium' && emailProfile.status === 'active' && (emailExpiry === null || emailExpiry > Date.now());
      const uidIsDefaultFree = uidProfile.plan !== 'premium' && uidProfile.status === 'active';
      if (emailPremiumActive && uidIsDefaultFree) {
        await sql`UPDATE profiles SET plan='premium',status='active',expires_at=${emailProfile.expires_at || null},feature_overrides=COALESCE(feature_overrides,${emailProfile.feature_overrides ? JSON.stringify(emailProfile.feature_overrides) : null}::jsonb),limit_overrides=CASE WHEN limit_overrides='{}'::jsonb THEN ${JSON.stringify(emailProfile.limit_overrides || {})}::jsonb ELSE limit_overrides END,updated_at=now() WHERE id=${identity.localId}`;
      }
    }
    await sql`UPDATE profiles SET email=${identity.email},display_name=CASE WHEN display_name='' THEN ${identity.displayName || identity.email.split('@')[0]} ELSE display_name END,updated_at=now() WHERE id=${identity.localId}`;

    const rows = await sql`SELECT id,email,plan,status,expires_at,feature_overrides,limit_overrides FROM profiles WHERE id=${identity.localId} LIMIT 1`;
    if (!rows.length) return NextResponse.json({ error: 'Profil akun tidak ditemukan' }, { status: 404 });
    const profile = rows[0];
    if (profile.status === 'blocked') {
      return NextResponse.json({ error: 'Akun ini diblokir oleh administrator', status: 'blocked' }, { status: 403, headers: { 'Cache-Control': 'no-store' } });
    }
    const expiry = profile.expires_at ? new Date(profile.expires_at) : null;
    const isExpired = !!expiry && expiry.getTime() <= Date.now();
    const isPremium = profile.plan === 'premium' && profile.status === 'active' && !isExpired;
    const planCode = isPremium ? 'premium' : 'free';
    const plans = await sql`SELECT code,name,features,limits FROM plans WHERE code=${planCode} LIMIT 1`;
    const plan = plans[0] || { code: planCode, name: isPremium ? 'Premium' : 'Free', features: ['base_launcher'], limits: { instances: 3, accounts: 2, ai_daily: 5 } };
    const features = Array.isArray(profile.feature_overrides) ? profile.feature_overrides : plan.features;
    const limits = { ...(plan.limits || {}), ...(profile.limit_overrides || {}) };

    return NextResponse.json({
      success: true,
      uid: identity.localId,
      email: identity.email,
      plan: plan.code,
      planName: plan.name,
      isActivated: isPremium,
      status: profile.status,
      expiresAt: profile.expires_at ? new Date(profile.expires_at).toISOString() : null,
      features,
      limits,
      serverTime: new Date().toISOString(),
    }, { headers: { 'Cache-Control': 'no-store, private' } });
  } catch (error) {
    console.error('[app/entitlements]', error);
    return NextResponse.json({ error: 'Gagal memuat hak akses akun' }, { status: 503 });
  }
}
