import { NextRequest,NextResponse } from 'next/server';
import { database,ensureSchema } from '@/lib/db';
export const runtime='nodejs';
const FIREBASE_WEB_API_KEY='AIzaSyDIqvaTHjuwNboBXcVDoY1JYJntS0ECsJ0';
async function identity(token:string){
 const r=await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${FIREBASE_WEB_API_KEY}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({idToken:token}),cache:'no-store'});
 if(!r.ok)return null;const j=await r.json(),u=j?.users?.[0];return u?.localId&&u?.email?{id:String(u.localId),email:String(u.email).toLowerCase()}:null;
}
export async function POST(req:NextRequest){
 const auth=req.headers.get('authorization')||'',token=auth.startsWith('Bearer ')?auth.slice(7).trim():'';
 if(!token)return NextResponse.json({error:'Bearer token diperlukan.'},{status:401});
 try{
  const user=await identity(token);if(!user)return NextResponse.json({error:'Sesi akun tidak valid.'},{status:401});
  const body=await req.json(),key=String(body.licenseKey||'').trim().toUpperCase();
  if(!key)return NextResponse.json({error:'Masukkan kode aktivasi.'},{status:400});
  const deviceId=String(body.deviceId||'').trim(),deviceName=String(body.deviceName||'Android device').trim().slice(0,120);
  if(!/^[a-f0-9]{64}$/i.test(deviceId))return NextResponse.json({error:'Identitas perangkat tidak valid.'},{status:400});
  await ensureSchema();const sql=database();
  const profile=await sql`SELECT id,email,status FROM profiles WHERE id=${user.id} OR lower(email)=${user.email} ORDER BY CASE WHEN id=${user.id} THEN 0 ELSE 1 END LIMIT 1`;
  if(!profile.length)return NextResponse.json({error:'Profil member tidak ditemukan.'},{status:404});
  if(profile[0].status==='blocked')return NextResponse.json({error:'Akun diblokir.'},{status:403});
  const keys=await sql`SELECT * FROM license_keys WHERE license_key=${key} LIMIT 1`;
  if(!keys.length)return NextResponse.json({error:'Kode aktivasi tidak ditemukan.'},{status:404});
  const license=keys[0];
  if(license.status==='revoked')return NextResponse.json({error:'Kode aktivasi telah dinonaktifkan.'},{status:403});
  if(license.status!=='unused'&&license.user_id!==user.id)return NextResponse.json({error:'Kode sudah digunakan oleh akun lain.'},{status:409});
  const bound=await sql`SELECT device_id FROM license_devices WHERE license_id=${license.id} AND user_id=${user.id} AND device_id=${deviceId} LIMIT 1`;
  if(!bound.length){
   const countRows=await sql`SELECT count(*)::int AS total FROM license_devices WHERE license_id=${license.id}`;
   const maxDevices=Math.max(1,Number(license.max_devices)||1);
   if(Number(countRows[0]?.total||0)>=maxDevices)return NextResponse.json({error:`Batas perangkat lisensi tercapai (maksimal ${maxDevices} perangkat). Hubungi admin untuk mengatur ulang perangkat.`},{status:403});
  }
  const now=new Date(),expires=license.duration_days===null?null:new Date(now.getTime()+Number(license.duration_days)*86400000);
  const expiresAt=expires?expires.toISOString():null;
  await sql`INSERT INTO license_devices(license_id,user_id,device_id,device_name) VALUES(${String(license.id)},${user.id},${deviceId},${deviceName}) ON CONFLICT(license_id,device_id) DO UPDATE SET device_name=EXCLUDED.device_name,last_seen_at=now()`;
  await sql`UPDATE license_keys SET status='active',user_id=${user.id},activated_at=COALESCE(activated_at,now()),expires_at=${expiresAt} WHERE id=${license.id} AND (status='unused' OR user_id=${user.id})`;
  await sql`UPDATE profiles SET id=${user.id},email=${user.email},license_id=${String(license.id)},plan='premium',status='active',expires_at=${expiresAt},updated_at=now() WHERE id=${profile[0].id}`;
  await sql`INSERT INTO licenses(id,user_id,email,plan,status,expires_at) VALUES(${String(license.id)},${user.id},${user.email},'premium','active',${expiresAt}) ON CONFLICT(id) DO UPDATE SET user_id=EXCLUDED.user_id,email=EXCLUDED.email,status='active',expires_at=EXCLUDED.expires_at`;
  await sql`INSERT INTO admin_audit_logs(actor,action,detail) VALUES(${user.id},'license.activate',${JSON.stringify({licenseId:license.id,expiresAt})}::jsonb)`;
  return NextResponse.json({success:true,isActivated:true,tier:license.tier||'premium',expiresAt:expiresAt||'lifetime',message:'Premium berhasil diaktifkan.'});
 }catch(e){console.error('[activate]',e);return NextResponse.json({error:'Gagal mengaktifkan kode. Coba lagi.'},{status:503});}
}
