import { NextRequest,NextResponse } from 'next/server';
import { createHmac,randomUUID } from 'node:crypto';
import { ensureSocialSchema,socialDatabase,isSocialFeatureEnabled } from '@/lib/social-db';
import { database,ensureSchema } from '@/lib/db';
import { verifySocialIdentity } from '@/lib/social-auth';
import { decryptLiveKitSecret } from '@/lib/livekit-config';
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
 await ensureSchema();
 const entitlement=await database()`SELECT p.plan,p.status,p.expires_at,pl.features FROM profiles p LEFT JOIN plans pl ON pl.code=p.plan WHERE p.id=${user.uid} LIMIT 1`;
 const account=entitlement[0];
 const features=Array.isArray(account?.features)?account.features:[];
 const expiry=account?.expires_at?new Date(account.expires_at).getTime():null;
 const active=account?.status==='active'&&(!expiry||expiry>Date.now());
 if(!active||(!features.includes('voice_rooms')&&account?.plan!=='premium'))return NextResponse.json({error:'Voice Room TG hanya tersedia untuk akun Premium yang aktif.'},{status:403});

 const configRows=await database()`SELECT value FROM app_settings WHERE key='social' LIMIT 1`;
 const config=(configRows[0]?.value||{}) as Record<string,any>;
 const serverUrl=String(config.livekitServerUrl||'').trim();
 let apiKey='',secret='';try{apiKey=config.livekitApiKeyEncrypted?decryptLiveKitSecret(String(config.livekitApiKeyEncrypted)):'';secret=config.livekitApiSecretEncrypted?decryptLiveKitSecret(String(config.livekitApiSecretEncrypted)):'';}catch(error){console.error('[tg-livekit-config-decrypt]',error);}
 if(!serverUrl||!apiKey||!secret)return NextResponse.json({error:'Konfigurasi LiveKit belum lengkap. Hubungi admin TG Launcher.'},{status:503});
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
   if(!/^[A-Za-z0-9_-]{1,80}$/.test(roomName))return NextResponse.json({error:'ID voice room tidak valid'},{status:400});
   let rows=await sql`SELECT livekit_room_name FROM social_voice_rooms WHERE (id=${roomName} OR livekit_room_name=${roomName}) AND is_active=true LIMIT 1`;
   // Android voice rooms are created in the existing TG Social room list.
   // Register the same room ID in the LiveKit registry on first join so the
   // two room systems stay in sync instead of returning a false 404.
   if(!rows.length){
    const livekitRoomName='tg-social-'+roomName;
    await sql`INSERT INTO social_voice_rooms(id,room_name,created_by,livekit_room_name,visibility,max_participants) VALUES(${roomName},${roomName},${user.uid},${livekitRoomName},'friends',16) ON CONFLICT(id) DO NOTHING`;
    rows=await sql`SELECT livekit_room_name FROM social_voice_rooms WHERE (id=${roomName} OR livekit_room_name=${roomName}) AND is_active=true LIMIT 1`;
   }
   if(!rows.length)return NextResponse.json({error:'Voice room tidak ditemukan atau sudah ditutup'},{status:404});
   roomName=String(rows[0].livekit_room_name);
  }else return NextResponse.json({error:'Aksi voice tidak didukung'},{status:400});
  const token=signLiveKitToken(apiKey,secret,user.uid,roomName);
  return NextResponse.json({success:true,serverUrl,roomName,token,expiresIn:3600},{headers:{'Cache-Control':'no-store'}});
 }catch(error){console.error('[tg-social-voice-token]',error);return NextResponse.json({error:'Gagal membuat token voice TG'},{status:503});}
}

export async function GET(req:NextRequest){
 const user=await verifySocialIdentity(req);if(!user)return NextResponse.json({error:'Token TG tidak valid'},{status:401});
 if(!(await isSocialFeatureEnabled('voiceEnabled',true)))return NextResponse.json({error:'Voice TG dinonaktifkan oleh admin.'},{status:403});
 try{
  await ensureSocialSchema();const sql=socialDatabase();
  const rows=await sql`SELECT r.id,r.room_name,r.created_by,r.visibility,r.max_participants,r.created_at,p.username AS host_name FROM social_voice_rooms r LEFT JOIN social_profiles p ON p.user_id=r.created_by WHERE r.is_active=true ORDER BY r.created_at DESC LIMIT 100`;
  return NextResponse.json({success:true,rooms:rows},{headers:{'Cache-Control':'no-store'}});
 }catch(error){console.error('[tg-social-voice-list]',error);return NextResponse.json({error:'Gagal memuat voice room TG'},{status:503});}
}
