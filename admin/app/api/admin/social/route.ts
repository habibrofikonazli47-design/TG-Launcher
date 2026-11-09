import { NextRequest,NextResponse } from 'next/server';
import { getCookieName,verifySession } from '@/lib/server';
import { ensureSocialSchema,socialDatabase } from '@/lib/social-db';
export const runtime='nodejs';
export const dynamic='force-dynamic';
function authorized(req:NextRequest){return verifySession(req.cookies.get(getCookieName())?.value);}
export async function GET(req:NextRequest){
 if(!authorized(req))return NextResponse.json({error:'Unauthorized'},{status:401});
 try{
  await ensureSocialSchema();const sql=socialDatabase();
  const [stats,profiles,rooms,reports]=await Promise.all([
   sql`SELECT (SELECT count(*)::int FROM social_profiles) AS profiles,(SELECT count(*)::int FROM social_friendships WHERE status='accepted') AS friendships,(SELECT count(*)::int FROM social_messages WHERE deleted_at IS NULL) AS messages,(SELECT count(*)::int FROM social_voice_rooms WHERE is_active=true) AS active_voice_rooms,(SELECT count(*)::int FROM social_reports WHERE status IN ('open','reviewing')) AS open_reports`,
   sql`SELECT user_id,username,avatar_url,platform,presence,last_seen_at,created_at FROM social_profiles ORDER BY updated_at DESC LIMIT 100`,
   sql`SELECT id,room_name,created_by,visibility,max_participants,is_active,created_at FROM social_voice_rooms ORDER BY created_at DESC LIMIT 50`,
   sql`SELECT id,reporter_id,target_user_id,reason,details,status,created_at FROM social_reports ORDER BY created_at DESC LIMIT 100`
  ]);
  return NextResponse.json({success:true,stats:stats[0],profiles,rooms,reports},{headers:{'Cache-Control':'no-store'}});
 }catch(error){console.error('[admin/social GET]',error);return NextResponse.json({error:'Gagal memuat data Social TG'},{status:503});}
}
export async function PATCH(req:NextRequest){
 if(!authorized(req))return NextResponse.json({error:'Unauthorized'},{status:401});
 try{
  await ensureSocialSchema();const sql=socialDatabase();const body=await req.json();const action=String(body.action||'');
  if(action==='report_status'){
   const id=Number(body.id);const status=String(body.status||'');
   if(!Number.isSafeInteger(id)||id<=0||!['open','reviewing','resolved','dismissed'].includes(status))return NextResponse.json({error:'Data laporan tidak valid'},{status:400});
   const rows=await sql`UPDATE social_reports SET status=${status} WHERE id=${id} RETURNING id,status`;
   return rows.length?NextResponse.json({success:true,report:rows[0]}):NextResponse.json({error:'Laporan tidak ditemukan'},{status:404});
  }
  if(action==='close_room'){
   const id=String(body.id||'');if(!id)return NextResponse.json({error:'ID room diperlukan'},{status:400});
   const rows=await sql`UPDATE social_voice_rooms SET is_active=false,closed_at=now() WHERE id=${id} RETURNING id,is_active`;
   return rows.length?NextResponse.json({success:true,room:rows[0]}):NextResponse.json({error:'Voice room tidak ditemukan'},{status:404});
  }
  if(action==='remove_friendship'){
   const id=Number(body.id);if(!Number.isSafeInteger(id)||id<=0)return NextResponse.json({error:'ID pertemanan tidak valid'},{status:400});
   const rows=await sql`DELETE FROM social_friendships WHERE id=${id} RETURNING id`;
   return rows.length?NextResponse.json({success:true}):NextResponse.json({error:'Relasi tidak ditemukan'},{status:404});
  }
  if(action==='set_presence'){
   const uid=String(body.userId||'');const presence=String(body.presence||'offline');
   if(!uid||!['online','offline','in_game','away'].includes(presence))return NextResponse.json({error:'Status pengguna tidak valid'},{status:400});
   const rows=await sql`UPDATE social_profiles SET presence=${presence},last_seen_at=now(),updated_at=now() WHERE user_id=${uid} RETURNING user_id,presence`;
   return rows.length?NextResponse.json({success:true,profile:rows[0]}):NextResponse.json({error:'Profil tidak ditemukan'},{status:404});
  }
  return NextResponse.json({error:'Aksi moderasi tidak didukung'},{status:400});
 }catch(error){console.error('[admin/social PATCH]',error);return NextResponse.json({error:'Gagal menjalankan moderasi Social TG'},{status:503});}
}
