import { NextRequest, NextResponse } from 'next/server';
import { ensureSocialSchema, socialDatabase } from '@/lib/social-db';
import { verifySocialIdentity } from '@/lib/social-auth';
export const runtime='nodejs';
export const dynamic='force-dynamic';
export async function GET(req: NextRequest) {
  const user = await verifySocialIdentity(req);
  if (!user) return NextResponse.json({ error:'Token TG tidak valid' }, { status:401 });
  try {
    await ensureSocialSchema();
    const sql=socialDatabase();
    await sql`INSERT INTO social_profiles(user_id,username,username_lower) VALUES(${user.uid},${user.username},${user.username.toLowerCase()}) ON CONFLICT(user_id) DO UPDATE SET username=CASE WHEN social_profiles.username='' THEN EXCLUDED.username ELSE social_profiles.username END, username_lower=CASE WHEN social_profiles.username='' THEN EXCLUDED.username_lower ELSE social_profiles.username_lower END, updated_at=now()`;
    const rows=await sql`SELECT user_id,username,avatar_url,bio,platform,presence,last_seen_at FROM social_profiles WHERE user_id=${user.uid} LIMIT 1`;
    return NextResponse.json({success:true,profile:rows[0]},{headers:{'Cache-Control':'no-store'}});
  } catch(error) { console.error('[social/profile]',error); return NextResponse.json({error:'Layanan Social TG belum tersedia'},{status:503}); }
}
export async function PATCH(req: NextRequest) {
  const user=await verifySocialIdentity(req);
  if(!user) return NextResponse.json({error:'Token TG tidak valid'},{status:401});
  try {
    const body=await req.json();
    const username=String(body.username||'').trim().slice(0,32);
    const bio=String(body.bio||'').trim().slice(0,160);
    const avatar=String(body.avatarUrl||'').trim().slice(0,2048);
    if(username.length<3 || !/^[a-zA-Z0-9_ .-]+$/.test(username)) return NextResponse.json({error:'Username 3-32 karakter; gunakan huruf, angka, spasi, titik, atau garis bawah.'},{status:400});
    await ensureSocialSchema();
    const sql=socialDatabase();
    const rows=await sql`UPDATE social_profiles SET username=${username},username_lower=${username.toLowerCase()},bio=${bio},avatar_url=${avatar},updated_at=now() WHERE user_id=${user.uid} RETURNING user_id,username,avatar_url,bio,platform,presence,last_seen_at`;
    if(!rows.length) return NextResponse.json({error:'Profil belum dibuat; panggil GET terlebih dahulu.'},{status:404});
    return NextResponse.json({success:true,profile:rows[0]});
  } catch(error:any) {
    if(error?.code==='23505') return NextResponse.json({error:'Username sudah digunakan.'},{status:409});
    console.error('[social/profile PATCH]',error); return NextResponse.json({error:'Gagal memperbarui profil'},{status:503});
  }
}

export async function POST(req: NextRequest) { return PATCH(req); }
