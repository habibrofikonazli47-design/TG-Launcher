import { createHmac, timingSafeEqual } from 'node:crypto';
import { NextRequest, NextResponse } from 'next/server';
import { getCookieName, verifySession } from '@/lib/server';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest) {
  if (!verifySession(request.cookies.get(getCookieName())?.value)) {
    return NextResponse.json({ error: 'Admin session tidak valid. Login kembali lalu hubungkan Google Drive.' }, { status: 401 });
  }
  const params = request.nextUrl.searchParams;
  const code = params.get('code');
  const state = params.get('state') || '';
  if (params.get('error')) return NextResponse.json({ error: 'Izin Google Drive tidak diberikan: ' + params.get('error') }, { status: 400 });
  if (!code || !state.includes('.')) return NextResponse.json({ error: 'Kode OAuth atau state tidak ditemukan.' }, { status: 400 });

  const [payload, suppliedSignature] = state.split('.');
  const secret = process.env.ADMIN_SESSION_SECRET;
  if (!secret) return NextResponse.json({ error: 'ADMIN_SESSION_SECRET belum dikonfigurasi.' }, { status: 503 });
  const expected = createHmac('sha256', secret).update(payload).digest();
  let supplied: Buffer;
  try { supplied = Buffer.from(suppliedSignature, 'base64url'); } catch { supplied = Buffer.alloc(0); }
  if (expected.length !== supplied.length || !timingSafeEqual(expected, supplied)) {
    return NextResponse.json({ error: 'State OAuth tidak valid.' }, { status: 400 });
  }

  let stateData: { exp: number; redirectUri: string };
  try { stateData = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')); }
  catch { return NextResponse.json({ error: 'State OAuth rusak.' }, { status: 400 }); }
  if (!stateData.exp || stateData.exp < Math.floor(Date.now() / 1000) || !stateData.redirectUri) {
    return NextResponse.json({ error: 'Sesi OAuth kedaluwarsa. Mulai koneksi ulang.' }, { status: 400 });
  }

  const body = new URLSearchParams({
    code,
    client_id: process.env.GOOGLE_DRIVE_CLIENT_ID || '',
    client_secret: process.env.GOOGLE_DRIVE_CLIENT_SECRET || '',
    redirect_uri: stateData.redirectUri,
    grant_type: 'authorization_code',
  });
  const response = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
    cache: 'no-store',
  });
  const tokens = await response.json();
  if (!response.ok || !tokens.refresh_token) {
    return NextResponse.json({
      error: tokens.error_description || tokens.error || 'Google tidak mengembalikan refresh token. Cabut izin aplikasi di akun Google lalu coba hubungkan lagi.',
    }, { status: 400 });
  }

  // Display the refresh token only to the authenticated administrator, once.
  // Copy it directly into Vercel as GOOGLE_DRIVE_REFRESH_TOKEN; never commit it.
  return NextResponse.json({
    connected: true,
    account: tokens.scope || 'Google Drive',
    refreshToken: tokens.refresh_token,
    nextStep: 'Salin refreshToken ke Vercel Environment Variable GOOGLE_DRIVE_REFRESH_TOKEN, lalu redeploy project tg-launcher-admin. Jangan kirim token ini ke chat atau GitHub.',
  }, { headers: { 'Cache-Control': 'no-store, max-age=0', 'Pragma': 'no-cache' } });
}
