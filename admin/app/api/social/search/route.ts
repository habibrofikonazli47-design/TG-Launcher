import { NextRequest,NextResponse } from 'next/server';
import { ensureSocialSchema,socialDatabase } from '@/lib/social-db';
import { verifySocialIdentity } from '@/lib/social-auth';
export const runtime='nodejs';
export const dynamic='force-dynamic';
export async function GET(req:NextRequest){
 const user=await verifySocialIdentity(req);if(!user)return NextResponse.json({error:'Token TG tidak valid'},{status:401});
 try{
  await ensureSocialSchema();const sql=socialDatabase();
  const q=String(req.nextUrl.searchParams.get('q')||'').trim().slice(0,32);
  const online=req.nextUrl.searchParams.get('online')==='true';
  const limit=Math.max(1,Math.min(50,Number(req.nextUrl.searchParams.get('limit')||20)||20));
  const pattern='%'+q.replace(/[\\%_]/g,'\\$&')+'%';
  const rows=online
   ? await sql`SELECT user_id,username,avatar_url,bio,platform,presence,last_seen_at FROM social_profiles WHERE user_id<>${user.uid} AND presence IN ('online','in_game') AND last_seen_at>now()-interval '90 seconds' ORDER BY last_seen_at DESC LIMIT ${limit}`
   : await sql`SELECT user_id,username,avatar_url,bio,platform,presence,last_seen_at FROM social_profiles WHERE user_id<>${user.uid} AND username_lower LIKE ${pattern} ORDER BY CASE WHEN presence='online' THEN 0 WHEN presence='in_game' THEN 1 ELSE 2 END,username_lower LIMIT ${limit}`;
  return NextResponse.json({success:true,items:rows},{headers:{'Cache-Control':'no-store'}});
 }catch(error){console.error('[social/search]',error);return NextResponse.json({error:'Gagal mencari pengguna TG'},{status:503});}
}
