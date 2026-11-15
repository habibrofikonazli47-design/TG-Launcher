import { createHmac, randomBytes } from 'node:crypto';
import { NextRequest, NextResponse } from 'next/server';
import { getCookieName, verifySession } from '@/lib/server';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest) {
  if (!verifySession(request.cookies.get(getCookieName())?.value)) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }
  const clientId = process.env.GOOGLE_DRIVE_CLIENT_ID?.trim();
  const secret = process.env.ADMIN_SESSION_SECRET;
  if (!clientId || !secret) {
    return NextResponse.json({ error: 'GOOGLE_DRIVE_CLIENT_ID dan ADMIN_SESSION_SECRET harus dikonfigurasi di Vercel.' }, { status: 503 });
  }
  const redirectUri = process.env.GOOGLE_DRIVE_REDIRECT_URI?.trim()
    || new URL('/api/admin/storage/drive/callback', request.url).toString();
  const payload = Buffer.from(JSON.stringify({
    exp: Math.floor(Date.now() / 1000) + 10 * 60,
    nonce: randomBytes(16).toString('hex'),
    redirectUri,
  })).toString('base64url');
  const signature = createHmac('sha256', secret).update(payload).digest('base64url');
  const state = payload + '.' + signature;
  const url = new URL('https://accounts.google.com/o/oauth2/v2/auth');
  url.searchParams.set('client_id', clientId);
  url.searchParams.set('redirect_uri', redirectUri);
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('access_type', 'offline');
  url.searchParams.set('prompt', 'consent');
  url.searchParams.set('include_granted_scopes', 'true');
  url.searchParams.set('scope', 'https://www.googleapis.com/auth/drive.file');
  url.searchParams.set('state', state);
  return NextResponse.redirect(url);
}
