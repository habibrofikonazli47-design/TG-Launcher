import { NextRequest,NextResponse } from 'next/server';
import { createHmac,randomUUID } from 'node:crypto';
import { isSocialFeatureEnabled } from '@/lib/social-db';
import { proxySocialRequest } from '@/lib/social-supabase';
import { database,ensureSchema } from '@/lib/db';
import { verifySocialIdentity } from '@/lib/social-auth';
import { decryptLiveKitSecret } from '@/lib/livekit-config';
export const runtime='nodejs';
export const dynamic='force-dynamic';
function b64(value:unknown){return Buffer.from(JSON.stringify(value)).toString('base64url');}
async function hasVoiceRoomEntitlement(uid:string){
 await ensureSchema();
 const rows=await database()`SELECT p.plan,p.status,p.expires_at,p.feature_overrides,pl.features FROM profiles p LEFT JOIN plans pl ON pl.code=p.plan WHERE p.id=${uid} LIMIT 1`;
 const profile=rows[0];
 if(!profile||profile.status!=='active')return false;
 const expiry=profile.expires_at?new Date(profile.expires_at).getTime():null;
 if(expiry!==null&&(!Number.isFinite(expiry)||expiry<=Date.now()))return false;
 const overrides=Array.isArray(profile.feature_overrides)?profile.feature_overrides:[];
 const features=overrides.length>0?overrides:(Array.isArray(profile.features)?profile.features:[]);
 // Explicit per-user overrides remain authoritative. Otherwise an active,
 // unexpired Premium plan grants Premium features even if a legacy plan row
 // has not yet been backfilled with the voice_rooms feature key.
 if(overrides.length>0)return features.includes('voice_rooms');
 return profile.plan==='premium' || features.includes('voice_rooms');
}
function signLiveKitToken(apiKey:string,secret:string,identity:string,room:string){
 const now=Math.floor(Date.now()/1000);
 const header=b64({alg:'HS256',typ:'JWT'});
 const payload=b64({iss:apiKey,sub:identity,nbf:now-5,iat:now,exp:now+3600,video:{roomJoin:true,room,canPublish:true,canSubscribe:true,canPublishData:true}});
 const data=header+'.'+payload;
 const signature=createHmac('sha256',secret).update(data).digest('base64url');
 return data+'.'+signature;
}
export async function POST(req:NextRequest){
 const socialRequest=req.clone();
 const user=await verifySocialIdentity(req);if(!user)return NextResponse.json({error:'Token TG tidak valid'},{status:401});
 if(!(await isSocialFeatureEnabled('voiceEnabled',true))||!(await isSocialFeatureEnabled('livekitEnabled',false)))return NextResponse.json({error:'Voice TG belum diaktifkan oleh admin.'},{status:403});
 if(!(await hasVoiceRoomEntitlement(user.uid)))return NextResponse.json({error:'Akses Voice Room belum diberikan untuk akun ini oleh admin TG Launcher.'},{status:403});
 const configRows=await database()`SELECT value FROM app_settings WHERE key='social' LIMIT 1`;
 const config=(configRows[0]?.value||{}) as Record<string,any>;
 const serverUrl=String(config.livekitServerUrl||'').trim();
 let apiKey='',secret='';try{apiKey=config.livekitApiKeyEncrypted?decryptLiveKitSecret(String(config.livekitApiKeyEncrypted)):'';secret=config.livekitApiSecretEncrypted?decryptLiveKitSecret(String(config.livekitApiSecretEncrypted)):'';}catch(error){console.error('[tg-livekit-config-decrypt]',error);}
 if(!serverUrl||!apiKey||!secret)return NextResponse.json({error:'Konfigurasi LiveKit belum lengkap. Hubungi admin TG Launcher.'},{status:503});
 try{
  const body=await req.json();const action=String(body.action||'join');
  if(!['create','join'].includes(action))return NextResponse.json({error:'Aksi voice tidak didukung'},{status:400});
  const result=await proxySocialRequest(socialRequest,'voice_rooms');
  if(!result.ok)return result;
  const payload=await result.json();
  if(!payload?.success||!payload?.roomName)return NextResponse.json({error:payload?.error||'Gagal menyiapkan voice room'},{status:result.status||503});
  const roomName=String(payload.roomName);
  const token=signLiveKitToken(apiKey,secret,user.uid,roomName);
  return NextResponse.json({success:true,serverUrl,roomId:payload.id||null,roomName,token,expiresIn:3600},{headers:{'Cache-Control':'no-store'}});
 }catch(error){console.error('[tg-social-voice-token]',error);return NextResponse.json({error:'Gagal membuat token voice TG'},{status:503});}
}
export async function GET(req:NextRequest){
 const user=await verifySocialIdentity(req);if(!user)return NextResponse.json({error:'Token TG tidak valid'},{status:401});
 if(!(await isSocialFeatureEnabled('voiceEnabled',true)))return NextResponse.json({error:'Voice TG dinonaktifkan oleh admin.'},{status:403});
 if(!(await isSocialFeatureEnabled('livekitEnabled',false)))return NextResponse.json({error:'Server Voice Room belum diaktifkan oleh admin TG Launcher.'},{status:403});
 if(!(await hasVoiceRoomEntitlement(user.uid)))return NextResponse.json({error:'Akses Voice Room belum diberikan untuk akun ini oleh admin TG.'},{status:403});
 return proxySocialRequest(req,'voice_rooms');
}
