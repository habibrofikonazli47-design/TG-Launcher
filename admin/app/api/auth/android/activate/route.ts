import { NextRequest,NextResponse } from 'next/server';
import { database,ensureSchema } from '@/lib/db';
export const runtime='nodejs';
const FIREBASE_WEB_API_KEY='AIzaSyDIqvaTHjuwNboBXcVDoY1jYJntS0ECsJ0';
async function identity(token:string){
 const r=await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${FIREBASE_WEB_API_KEY}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({idToken:token}),cache:'no-store'});
 if(!r.ok)return null;const j=await r.json(),u=j?.users?.[0];return u?.localId&&u?.email?{id:String(u.localId),email:String(u.email).toLowerCase()}:null;
}
export async function POST(req:NextRequest){
 const auth=req.headers.get('authorization')||'',token=auth.startsWith('Bearer ')?auth.slice(7).trim():'';
 if(!token)return NextResponse.json({error:'Bearer token diperlukan.'},{status:401});
 try{
  const user=await identity(token);if(!user)return NextResponse.json({error:'Sesi akun tidak valid.'},{status:401});
  const body=await req.json(),key=String(body.licenseKey||'').replace(/[^a-f0-9]/gi,'').toUpperCase();
  if(!key)return NextResponse.json({error:'Masukkan kode aktivasi.'},{status:400});
  if(!/^[A-F0-9]{16}$/.test(key))return NextResponse.json({error:'Format kode tidak valid. Gunakan 16 karakter (XXXX-XXXX-XXXX-XXXX).'}, {status:400});
  const deviceId=String(body.deviceId||'').trim(),deviceName=String(body.deviceName||'Android device').trim().slice(0,120);
  if(!/^[a-f0-9]{64}$/i.test(deviceId))return NextResponse.json({error:'Identitas perangkat tidak valid.'},{status:400});
  await ensureSchema();const sql=database();
  const profile=await sql`SELECT id,email,status FROM profiles WHERE id=${user.id} OR lower(email)=${user.email} ORDER BY CASE WHEN id=${user.id} THEN 0 ELSE 1 END LIMIT 1`;
  if(!profile.length)return NextResponse.json({error:'Profil member tidak ditemukan.'},{status:404});
  if(profile[0].status==='blocked')return NextResponse.json({error:'Akun diblokir.'},{status:403});
  const keys=await sql`SELECT * FROM license_keys WHERE regexp_replace(upper(license_key), '[^A-F0-9]', '', 'g')=${key} LIMIT 1`;
  if(!keys.length)return NextResponse.json({error:'Kode aktivasi tidak ditemukan.'},{status:404});
  const license=keys[0];
  if(license.status==='revoked')return NextResponse.json({error:'Kode aktivasi telah dinonaktifkan.'},{status:403});
  const alreadyActivatedByUser=license.status==='active'&&license.user_id===user.id;
  if(license.status==='unused'&&license.user_id&&license.user_id!==user.id)return NextResponse.json({error:'Kode ini pernah terikat ke akun lain dan tidak dapat dipindahkan.'},{status:409});
  if(license.status!=='unused'&&!alreadyActivatedByUser)return NextResponse.json({error:'Kode sudah digunakan oleh akun lain.'},{status:409});
  if(license.status==='unused'&&Number(license.used_count||0)>=Number(license.max_uses||1))return NextResponse.json({error:'Batas penggunaan kode aktivasi telah tercapai.'},{status:409});
  if(!alreadyActivatedByUser&&license.expires_at&&new Date(license.expires_at).getTime()<=Date.now())return NextResponse.json({error:'Kode aktivasi sudah kedaluwarsa.'},{status:403});
  if(alreadyActivatedByUser&&license.expires_at&&new Date(license.expires_at).getTime()<=Date.now())return NextResponse.json({error:'Masa aktif kode ini sudah berakhir. Hubungi admin untuk perpanjangan.'},{status:403});

  // Claim key and reserve device in one conditional UPDATE. This prevents two
  // simultaneous requests from different accounts consuming the same unused key,
  // and enforces the device limit after PostgreSQL serializes updates to this row.
  const maxDevices=Math.max(1,Number(license.max_devices)||1);
  const expiresAt=alreadyActivatedByUser
   ? (license.expires_at?new Date(license.expires_at).toISOString():null)
   : (license.duration_days===null?null:new Date(Date.now()+Number(license.duration_days)*86400000).toISOString());
  const claimed=await sql`UPDATE license_keys SET
    status='active',
    user_id=${user.id},
    activated_at=COALESCE(activated_at,now()),
    expires_at=${expiresAt},
    used_count=CASE WHEN status='unused' THEN used_count+1 ELSE used_count END
    WHERE id=${String(license.id)}
      AND (status='unused' OR (status='active' AND user_id=${user.id}))
      AND (expires_at IS NULL OR expires_at>now() OR (status='active' AND user_id=${user.id}))
      AND (
        EXISTS(SELECT 1 FROM license_devices d WHERE d.license_id=license_keys.id AND d.user_id=${user.id} AND d.device_id=${deviceId})
        OR (SELECT count(*) FROM license_devices d WHERE d.license_id=license_keys.id) < ${maxDevices}
      )
    RETURNING id`;
  if(!claimed.length){
   const latest=await sql`SELECT status,user_id,expires_at FROM license_keys WHERE id=${String(license.id)} LIMIT 1`;
   if(latest[0]?.user_id&&latest[0].user_id!==user.id)return NextResponse.json({error:'Kode sudah digunakan oleh akun lain.'},{status:409});
   const deviceCount=await sql`SELECT count(*)::int AS total FROM license_devices WHERE license_id=${String(license.id)}`;
   if(Number(deviceCount[0]?.total||0)>=maxDevices)return NextResponse.json({error:`Batas perangkat lisensi tercapai (maksimal ${maxDevices} perangkat). Hubungi admin untuk mengatur ulang perangkat.`},{status:403});
   return NextResponse.json({error:'Kode aktivasi tidak dapat digunakan atau sudah kedaluwarsa.'},{status:409});
  }
  await sql`INSERT INTO license_devices(license_id,user_id,device_id,device_name) VALUES(${String(license.id)},${user.id},${deviceId},${deviceName}) ON CONFLICT(license_id,device_id) DO UPDATE SET device_name=EXCLUDED.device_name,last_seen_at=now()`;
  await sql`INSERT INTO admin_audit_logs(actor,action,detail) VALUES(${user.id},'license.activate',${JSON.stringify({licenseId:license.id,expiresAt})}::jsonb)`;
  await sql`UPDATE profiles SET id=${user.id},email=${user.email},license_id=${String(license.id)},plan='premium',status='active',expires_at=${expiresAt},updated_at=now() WHERE id=${profile[0].id}`;
  await sql`INSERT INTO licenses(id,user_id,email,plan,status,expires_at) VALUES(${String(license.id)},${user.id},${user.email},'premium','active',${expiresAt}) ON CONFLICT(id) DO UPDATE SET user_id=EXCLUDED.user_id,email=EXCLUDED.email,status='active',expires_at=EXCLUDED.expires_at`;
  await sql`INSERT INTO admin_audit_logs(actor,action,detail) VALUES(${user.id},'license.activate',${JSON.stringify({licenseId:license.id,expiresAt})}::jsonb)`;
  return NextResponse.json({success:true,isActivated:true,tier:license.tier||'premium',expiresAt:expiresAt||'lifetime',message:'Premium berhasil diaktifkan.'});
 }catch(e){console.error('[activate]',e);return NextResponse.json({error:'Gagal mengaktifkan kode. Coba lagi.'},{status:503});}
}
