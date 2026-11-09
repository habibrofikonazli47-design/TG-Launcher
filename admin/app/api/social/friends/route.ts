import { NextRequest, NextResponse } from 'next/server';
import { ensureSocialSchema, socialDatabase } from '@/lib/social-db';
import { verifySocialIdentity } from '@/lib/social-auth';
export const runtime='nodejs';
export const dynamic='force-dynamic';
export async function GET(req: NextRequest) {
 const user=await verifySocialIdentity(req);
 if(!user) return NextResponse.json({error:'Token TG tidak valid'},{status:401});
 try {
  await ensureSocialSchema(); const sql=socialDatabase();
  const rows=await sql`SELECT f.id,f.requester_id,f.addressee_id,f.status,f.created_at,p.user_id,p.username,p.avatar_url,p.presence,p.last_seen_at
   FROM social_friendships f JOIN social_profiles p ON p.user_id=CASE WHEN f.requester_id=${user.uid} THEN f.addressee_id ELSE f.requester_id END
   WHERE (f.requester_id=${user.uid} OR f.addressee_id=${user.uid}) AND f.status<>'blocked'
   ORDER BY CASE WHEN f.status='pending' AND f.addressee_id=${user.uid} THEN 0 WHEN f.status='accepted' THEN 1 ELSE 2 END,f.updated_at DESC LIMIT 500`;
  return NextResponse.json({success:true,items:rows},{headers:{'Cache-Control':'no-store'}});
 } catch(error) { console.error('[social/friends]',error); return NextResponse.json({error:'Gagal memuat daftar teman'},{status:503}); }
}
export async function POST(req: NextRequest) {
 const user=await verifySocialIdentity(req);
 if(!user) return NextResponse.json({error:'Token TG tidak valid'},{status:401});
 try {
  const body=await req.json(); const action=String(body.action||'request');
  await ensureSocialSchema(); const sql=socialDatabase();
  if(action==='request') {
   const target=String(body.username||'').trim().toLowerCase();
   if(!target || target.length>32) return NextResponse.json({error:'Username tujuan tidak valid'},{status:400});
   const found=await sql`SELECT user_id FROM social_profiles WHERE username_lower=${target} LIMIT 1`;
   if(!found.length) return NextResponse.json({error:'Pengguna TG tidak ditemukan'},{status:404});
   if(found[0].user_id===user.uid) return NextResponse.json({error:'Tidak dapat menambahkan akun sendiri'},{status:400});
   const result=await sql`INSERT INTO social_friendships(requester_id,addressee_id,status) VALUES(${user.uid},${found[0].user_id},'pending') ON CONFLICT(requester_id,addressee_id) DO UPDATE SET status=CASE WHEN social_friendships.status='blocked' THEN 'blocked' ELSE 'pending' END,updated_at=now() RETURNING id,status`;
   return NextResponse.json({success:true,friendship:result[0]});
  }
  const id=Number(body.id);
  if(!Number.isSafeInteger(id)||id<=0) return NextResponse.json({error:'Permintaan teman tidak valid'},{status:400});
  if(action==='accept') {
   const result=await sql`UPDATE social_friendships SET status='accepted',updated_at=now() WHERE id=${id} AND addressee_id=${user.uid} AND status='pending' RETURNING id,status`;
   return result.length?NextResponse.json({success:true,friendship:result[0]}):NextResponse.json({error:'Permintaan tidak ditemukan'},{status:404});
  }
  if(action==='decline'||action==='remove') {
   const result=await sql`DELETE FROM social_friendships WHERE id=${id} AND (requester_id=${user.uid} OR addressee_id=${user.uid}) RETURNING id`;
   return result.length?NextResponse.json({success:true}):NextResponse.json({error:'Pertemanan tidak ditemukan'},{status:404});
  }
  return NextResponse.json({error:'Aksi tidak didukung'},{status:400});
 } catch(error) { console.error('[social/friends POST]',error); return NextResponse.json({error:'Gagal memproses pertemanan'},{status:503}); }
}
