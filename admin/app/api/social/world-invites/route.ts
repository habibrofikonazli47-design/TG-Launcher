import { NextRequest, NextResponse } from 'next/server';
import { sign as signEd25519, randomUUID } from 'node:crypto';
import { isSocialFeatureEnabled } from '@/lib/social-db';
import { proxySocialRequest } from '@/lib/social-supabase';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

function createWorldInviteAssertion() {
  const pem = process.env.TG_SOCIAL_ADMIN_SIGNING_PRIVATE_KEY?.replace(/\\n/g, '\n');
  if (!pem) return null;
  const now = Math.floor(Date.now() / 1000);
  const header = Buffer.from(JSON.stringify({ alg: 'EdDSA', typ: 'JWT' })).toString('base64url');
  const payload = Buffer.from(JSON.stringify({
    sub: 'tg-admin',
    aud: 'tg-world-invites-gate',
    iat: now,
    exp: now + 30,
    jti: randomUUID(),
  })).toString('base64url');
  const input = header + '.' + payload;
  return input + '.' + signEd25519(null, Buffer.from(input), pem).toString('base64url');
}

async function proxyWorldInvite(req: NextRequest) {
  if (!(await isSocialFeatureEnabled('worldInvitesEnabled', false))) {
    return NextResponse.json({ error: 'Fitur undang teman ke dunia privat belum diaktifkan.' }, { status: 403, headers: { 'Cache-Control': 'no-store' } });
  }
  const assertion = createWorldInviteAssertion();
  if (!assertion) {
    return NextResponse.json({ error: 'Layanan undangan dunia belum dikonfigurasi dengan aman.' }, { status: 503, headers: { 'Cache-Control': 'no-store' } });
  }
  return proxySocialRequest(req, 'world_invites', { 'x-tg-world-invites-assertion': assertion });
}

export async function GET(req: NextRequest) { return proxyWorldInvite(req); }
export async function POST(req: NextRequest) { return proxyWorldInvite(req); }
export async function PATCH(req: NextRequest) { return proxyWorldInvite(req); }
