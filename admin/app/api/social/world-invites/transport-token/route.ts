import { NextRequest, NextResponse } from 'next/server';
import { createHmac, sign as signEd25519, randomUUID } from 'node:crypto';
import { isSocialFeatureEnabled } from '@/lib/social-db';
import { proxySocialRequest } from '@/lib/social-supabase';
import { database } from '@/lib/db';
import { verifySocialIdentity } from '@/lib/social-auth';
import { decryptLiveKitSecret } from '@/lib/livekit-config';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

function b64(value: unknown) {
  return Buffer.from(JSON.stringify(value)).toString('base64url');
}

function createWorldInviteAssertion() {
  const pem = process.env.TG_SOCIAL_ADMIN_SIGNING_PRIVATE_KEY?.replace(/\\n/g, '\n');
  if (!pem) return null;
  const now = Math.floor(Date.now() / 1000);
  const header = b64({ alg: 'EdDSA', typ: 'JWT' });
  const payload = b64({
    sub: 'tg-admin',
    aud: 'tg-world-invites-gate',
    iat: now,
    exp: now + 30,
    jti: randomUUID(),
  });
  const input = header + '.' + payload;
  return input + '.' + signEd25519(null, Buffer.from(input), pem).toString('base64url');
}

function signWorldTransportToken(apiKey: string, secret: string, identity: string, room: string) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64({ alg: 'HS256', typ: 'JWT' });
  const payload = b64({
    iss: apiKey,
    sub: identity,
    nbf: now - 5,
    iat: now,
    exp: now + 900,
    video: {
      roomJoin: true,
      room,
      canPublish: false,
      canSubscribe: true,
      canPublishData: true,
    },
  });
  const input = header + '.' + payload;
  return input + '.' + createHmac('sha256', secret).update(input).digest('base64url');
}

export async function POST(req: NextRequest) {
  const user = await verifySocialIdentity(req);
  if (!user) return NextResponse.json({ error: 'Token TG tidak valid' }, { status: 401 });
  if (!(await isSocialFeatureEnabled('worldInvitesEnabled', false))) {
    return NextResponse.json({ error: 'Fitur dunia privat belum diaktifkan oleh admin.' }, { status: 403 });
  }

  const assertion = createWorldInviteAssertion();
  if (!assertion) {
    return NextResponse.json({ error: 'Layanan dunia privat belum dikonfigurasi dengan aman.' }, { status: 503 });
  }

  try {
    const body = await req.json();
    const sessionId = String(body.sessionId || '').trim();
    if (!/^[0-9a-f-]{36}$/i.test(sessionId)) {
      return NextResponse.json({ error: 'ID sesi tidak valid' }, { status: 400 });
    }

    const socialRequest = new Request(req.url, {
      method: 'POST',
      headers: {
        Authorization: req.headers.get('authorization') || '',
        'Content-Type': 'application/json',
        'x-tg-world-invites-assertion': assertion,
      },
      body: JSON.stringify({ action: 'transport_access', sessionId }),
    });
    const socialResponse = await proxySocialRequest(
      socialRequest,
      'world_invites',
      { 'x-tg-world-invites-assertion': assertion },
    );
    const access = await socialResponse.json().catch(() => null);
    if (!socialResponse.ok || !access?.success || !access?.roomName) {
      return NextResponse.json(
        { error: access?.error || 'Akses transport dunia ditolak' },
        { status: socialResponse.status || 503 },
      );
    }

    const rows = await database()\`SELECT value FROM app_settings WHERE key='social' LIMIT 1\`;
    const config = (rows[0]?.value || {}) as Record<string, unknown>;
    const serverUrl = String(config.livekitServerUrl || '').trim();
    let apiKey = '';
    let secret = '';
    try {
      apiKey = config.livekitApiKeyEncrypted ? decryptLiveKitSecret(String(config.livekitApiKeyEncrypted)) : '';
      secret = config.livekitApiSecretEncrypted ? decryptLiveKitSecret(String(config.livekitApiSecretEncrypted)) : '';
    } catch (error) {
      console.error('[tg-world-transport-config-decrypt]', error);
    }
    if (!serverUrl || !apiKey || !secret) {
      return NextResponse.json({ error: 'Relay LiveKit belum dikonfigurasi oleh admin.' }, { status: 503 });
    }

    const token = signWorldTransportToken(apiKey, secret, user.uid, String(access.roomName));
    return NextResponse.json({
      success: true,
      serverUrl,
      token,
      roomName: access.roomName,
      sessionId,
      role: access.role,
      expiresIn: 900,
    }, { headers: { 'Cache-Control': 'no-store' } });
  } catch (error) {
    console.error('[tg-world-transport-token]', error);
    return NextResponse.json({ error: 'Gagal menyiapkan koneksi dunia privat' }, { status: 503 });
  }
}
