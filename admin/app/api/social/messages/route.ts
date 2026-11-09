import { NextRequest, NextResponse } from 'next/server';
import { ensureSocialSchema, socialDatabase } from '@/lib/social-db';
import { verifySocialIdentity } from '@/lib/social-auth';
export const runtime='nodejs';
export const dynamic='force-dynamic';
export async function GET(req:NextRequest){
 const user=await verifySocialIdentity(req); if(!user)return NextResponse.json({error:'Token TG tidak valid'},{status:401});
 const conversationId=String(req.nextUrl.searchParams.get('conversationId')||'');
 if(!conversationId)return NextResponse.json({error:'conversationId diperlukan'},{status:400});
 try{
  await ensureSocialSchema();const sql=socialDatabase();
  const member=await sql`SELECT 1 FROM social_conversation_members WHERE conversation_id=${conversationId} AND user_id=${user.uid} LIMIT 1`;
  if(!member.length)return NextResponse.json({error:'Akses percakapan ditolak'},{status:403});
  const rows=await sql`SELECT id,sender_id,body,attachment_url,created_at,edited_at FROM social_messages WHERE conversation_id=${conversationId} AND deleted_at IS NULL ORDER BY created_at DESC LIMIT 100`;
  return NextResponse.json({success:true,messages:rows.reverse()},{headers:{'Cache-Control':'no-store'}});
 }catch(error){console.error('[social/messages GET]',error);return NextResponse.json({error:'Gagal memuat pesan'},{status:503});}
}
export async function POST(req:NextRequest){
 const user=await verifySocialIdentity(req);if(!user)return NextResponse.json({error:'Token TG tidak valid'},{status:401});
 try{
  const body=await req.json();const target=String(body.targetUserId||'');const text=String(body.message||'').trim();
  if(!target||target===user.uid||!text||text.length>4000)return NextResponse.json({error:'Penerima atau isi pesan tidak valid'},{status:400});
  await ensureSocialSchema();const sql=socialDatabase();
  const friendship=await sql`SELECT id FROM social_friendships WHERE status='accepted' AND ((requester_id=${user.uid} AND addressee_id=${target}) OR (requester_id=${target} AND addressee_id=${user.uid})) LIMIT 1`;
  if(!friendship.length)return NextResponse.json({error:'Pesan hanya dapat dikirim kepada teman yang sudah menerima permintaan'},{status:403});
  const a=user.uid<target?user.uid:target;const b=user.uid<target?target:user.uid;const conversationId='dm_'+a+'_'+b;
  await sql`INSERT INTO social_conversations(id,conversation_type,created_by) VALUES(${conversationId},'direct',${user.uid}) ON CONFLICT(id) DO NOTHING`;
  await sql`INSERT INTO social_conversation_members(conversation_id,user_id) VALUES(${conversationId},${user.uid}),(${conversationId},${target}) ON CONFLICT DO NOTHING`;
  const id=crypto.randomUUID();
  const rows=await sql`INSERT INTO social_messages(id,conversation_id,sender_id,body) VALUES(${id},${conversationId},${user.uid},${text}) RETURNING id,conversation_id,sender_id,body,created_at`;
  return NextResponse.json({success:true,message:rows[0]},{status:201});
 }catch(error){console.error('[social/messages POST]',error);return NextResponse.json({error:'Gagal mengirim pesan'},{status:503});}
}
