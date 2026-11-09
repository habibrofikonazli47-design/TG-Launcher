import { NextRequest,NextResponse } from 'next/server';
import { createHmac,randomUUID } from 'node:crypto';
import { ensureSocialSchema,socialDatabase,isSocialFeatureEnabled } from '@/lib/social-db';
import { verifySocialIdentity } from '@/lib/social-auth';
export const runtime='nodejs';
export const dynamic='force-dynamic';
function b64(value:unknown){return Buffer.from(JSON.stringify(value)).toString('base64url');}
function signLiveKitToken(apiKey:string,secret:string,identity:string,room:string){
 const now=Math.floor(Date.now()/1000);
 const header=b64({alg:'HS256',typ:'JWT'});
 const payload=b64({iss:apiKey,sub:identity,nbf:now-5,iat:now,exp:now+3600,video:{roomJoin:true,room,canPublish:true,canSubscribe:true,canPublishData:true}});
 const data=header+'.'+payload;
 const signature=createHmac('sha256',secret).update(data).digest('base64url');
 return data+'.'+signature;
}
export async function POST(req:NextRequest){
 const user=await verifySocialIdentity(req);if(!user)return NextResponse.json({error:'Token TG tidak valid'},{status:401});
 if(!(await isSocialFeatureEnabled('voiceEnabled',true))||!(await isSocialFeatureEnabled('livekitEnabled',false)))return NextResponse.json({error:'Voice TG belum diaktifkan oleh admin.'},{status:403});
 const serverUrl=process.env.TG_LIVEKIT_URL,apiKey=process.env.TG_LIVEKIT_API_KEY,secret=process.env.TG_LIVEKIT_API_SECRET;
 if(!serverUrl||!apiKey||!secret)return NextResponse.json({error:'Server LiveKit TG belum dikonfigurasi.'},{status:503});
 try{
  await ensureSocialSchema();const sql=socialDatabase();const body=await req.json();
  const action=String(body.action||'join');let roomName=String(body.roomName||'').trim().slice(0,80);
  if(action==='create'){
   if(!roomName)return NextResponse.json({error:'Nama voice room diperlukan'},{status:400});
   const id='tg_'+randomUUID().replace(/-/g,'').slice(0,20);
   const livekitRoom='tg-social-'+id;
   const visibility=['public','friends','private'].includes(String(body.visibility))?String(body.visibility):'friends';
   const max=Math.max(2,Math.min(50,Number(body.maxParticipants)||16));
   await sql`INSERT INTO social_voice_rooms(id,room_name,created_by,livekit_room_name,visibility,max_participants) VALUES(${id},${roomName},${user.uid},${livekitRoom},${visibility},${max})`;
   roomName=livekitRoom;
  }else if(action==='join'){
   if(!roomName)return NextResponse.json({error:'Room voice diperlukan'},{status:400});
   const rows=await sql`SELECT livekit_room_name FROM social_voice_rooms WHERE (id=${roomName} OR livekit_room_name=${roomName}) AND is_active=true LIMIT 1`;
   if(!rows.length)return NextResponse.json({error:'Voice room tidak ditemukan atau sudah ditutup'},{status:404});
   roomName=String(rows[0].livekit_room_name);
  }else return NextResponse.json({error:'Aksi voice tidak didukung'},{status:400});
  const token=signLiveKitToken(apiKey,secret,user.uid,roomName);
  return NextResponse.json({success:true,serverUrl,roomName,token,expiresIn:3600},{headers:{'Cache-Control':'no-store'}});
 }catch(error){console.error('[tg-social-voice-token]',error);return NextResponse.json({error:'Gagal membuat token voice TG'},{status:503});}
}
