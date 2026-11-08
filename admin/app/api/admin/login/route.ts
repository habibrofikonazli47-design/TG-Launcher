import { NextResponse } from 'next/server';
import { makeSession, sessionCookie, verifyAdminPassword, requireSameOrigin } from '@/lib/server';
export const runtime = 'nodejs';
export async function POST(request: Request) {
  try {
    requireSameOrigin(request);
    const { username, password } = await request.json();
    if (!process.env.ADMIN_EMAIL || !process.env.ADMIN_PASSWORD_HASH || !process.env.ADMIN_SESSION_SECRET) return NextResponse.json({error:'Konfigurasi admin Vercel belum lengkap.'},{status:503});
    if (String(username||'').trim().toLowerCase() !== process.env.ADMIN_EMAIL.toLowerCase() || !verifyAdminPassword(String(password||''))) return NextResponse.json({error:'Kredensial admin salah.'},{status:401});
    const response = NextResponse.json({ok:true}); response.cookies.set(sessionCookie(makeSession(process.env.ADMIN_EMAIL))); return response;
  } catch (e:any) { return NextResponse.json({error:e.message||'Login gagal'},{status:400}); }
}
