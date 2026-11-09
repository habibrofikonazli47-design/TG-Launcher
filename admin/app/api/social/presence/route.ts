import { NextRequest,NextResponse } from 'next/server';
import { ensureSocialSchema,socialDatabase,isSocialFeatureEnabled } from '@/lib/social-db';
import { verifySocialIdentity } from '@/lib/social-auth';
export const runtime='nodejs';
export const dynamic='force-dynamic';
export async function POST(req:NextRequest){
 if (!(await isSocialFeatureEnabled('friendsEnabled'))) return NextResponse.json({ error: 'Presence sosial dinonaktifkan oleh admin TG.' }, { status: 403 });
 const user=await verifySocialIdentity(req);if(!user)return NextResponse.json({error:'Token TG tidak valid'},{status:401});
 try{
  const body=await req.json();const status=String(body.status||'online');
  if(!['online','offline','in_game','away'].includes(status))return NextResponse.json({error:'Status tidak valid'},{status:400});
  await ensureSocialSchema();const sql=socialDatabase();
  await sql`INSERT INTO social_profiles(user_id,username,username_lower,presence,last_seen_at) VALUES(${user.uid},${user.username},${user.username.toLowerCase()},${status},now()) ON CONFLICT(user_id) DO UPDATE SET presence=EXCLUDED.presence,last_seen_at=now(),updated_at=now()`;
  return NextResponse.json({success:true,status});
 }catch(error){console.error('[social/presence]',error);return NextResponse.json({error:'Gagal memperbarui status online'},{status:503});}
}
