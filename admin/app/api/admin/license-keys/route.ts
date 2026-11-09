import { NextRequest,NextResponse } from 'next/server';
import { randomBytes } from 'crypto';
import { database,ensureSchema } from '@/lib/db';
import { getCookieName,verifySession } from '@/lib/server';
export const runtime='nodejs';
function makeKey(){const raw=randomBytes(10).toString('hex').toUpperCase();return raw.match(/.{1,5}/g)!.join('-');}
export async function GET(req:NextRequest){
 if(!verifySession(req.cookies.get(getCookieName())?.value))return NextResponse.json({error:'Unauthorized'},{status:401});
 try{await ensureSchema();return NextResponse.json(await database()`SELECT id,license_key,plan,status,max_uses,used_count,expires_at,duration_days,max_devices,user_id,activated_at,created_at,(SELECT count(*)::int FROM license_devices d WHERE d.license_id=license_keys.id) AS device_count,(SELECT COALESCE(json_agg(json_build_object('device_id',d.device_id,'device_name',d.device_name,'last_seen_at',d.last_seen_at) ORDER BY d.last_seen_at DESC),'[]'::json) FROM license_devices d WHERE d.license_id=license_keys.id) AS devices FROM license_keys ORDER BY created_at DESC LIMIT 300`);}
 catch(e:any){return NextResponse.json({error:e.message},{status:503});}
}
export async function POST(req:NextRequest){
 if(!verifySession(req.cookies.get(getCookieName())?.value))return NextResponse.json({error:'Unauthorized'},{status:401});
 try{
  await ensureSchema();const b=await req.json();
  const days=b.durationDays===null?null:Number(b.durationDays),devices=Number(b.maxDevices||1),count=Number(b.maxUses||1);
  if((days!==null&&(!Number.isInteger(days)||days<1||days>36500))||!Number.isInteger(devices)||devices<1||devices>20||!Number.isInteger(count)||count<1||count>1000)return NextResponse.json({error:'Durasi, batas perangkat, atau jumlah penggunaan tidak valid.'},{status:400});
  const key=makeKey(),id=randomBytes(12).toString('hex');
  const rows=await database()`INSERT INTO license_keys(id,license_key,plan,status,max_uses,used_count,duration_days,max_devices,tier) VALUES(${id},${key},'premium','unused',${count},0,${days},${devices},${b.tier==='lifetime'?'lifetime':days===null?'lifetime':days>=365?'yearly':days>=28?'monthly':'custom'}) RETURNING id,license_key,plan,status,max_uses,used_count,duration_days,max_devices,created_at`;
  await database()`INSERT INTO admin_audit_logs(actor,action,detail) VALUES('admin','license.create',${JSON.stringify({id,durationDays:days,maxDevices:devices,maxUses:count})}::jsonb)`;
  return NextResponse.json(rows[0],{status:201});
 }catch(e:any){return NextResponse.json({error:e.message},{status:400});}
}
export async function PATCH(req:NextRequest){
 if(!verifySession(req.cookies.get(getCookieName())?.value))return NextResponse.json({error:'Unauthorized'},{status:401});
 try{await ensureSchema();const b=await req.json();if(!b.id||!['active','revoked','unused'].includes(b.status))return NextResponse.json({error:'Status lisensi tidak valid.'},{status:400});
 const current=await database()`SELECT id,user_id,used_count FROM license_keys WHERE id=${String(b.id)} LIMIT 1`;
 if(!current.length)return NextResponse.json({error:'Kode tidak ditemukan.'},{status:404});
 if(b.status==='unused'&&(current[0].user_id||Number(current[0].used_count||0)>0))return NextResponse.json({error:'Kode yang pernah digunakan tidak dapat direset ke status belum dipakai.'},{status:409});
 if(b.status==='active'&&!current[0].user_id)return NextResponse.json({error:'Kode belum pernah diaktifkan. Gunakan status belum dipakai.'},{status:409});
 const rows=await database()`UPDATE license_keys SET status=${b.status} WHERE id=${String(b.id)} RETURNING id,license_key,status,user_id`;
 if(!rows.length)return NextResponse.json({error:'Kode tidak ditemukan.'},{status:404});
 const license=rows[0];
 if(license.user_id){
  if(b.status==='revoked'){
   await database()`UPDATE profiles SET plan='free',updated_at=now() WHERE license_id=${license.id}`;
   await database()`UPDATE licenses SET status='revoked' WHERE id=${license.id}`;
  }else{
   await database()`UPDATE profiles SET plan='premium',updated_at=now() WHERE license_id=${license.id} AND status='active' AND (expires_at IS NULL OR expires_at>now())`;
   await database()`UPDATE licenses SET status='active' WHERE id=${license.id}`;
  }
 }
 await database()`INSERT INTO admin_audit_logs(actor,action,detail) VALUES('admin','license.status',${JSON.stringify({id:b.id,status:b.status})}::jsonb)`;
 return NextResponse.json({id:license.id,license_key:license.license_key,status:license.status});}catch(e:any){return NextResponse.json({error:e.message},{status:400});}
}


export async function DELETE(req:NextRequest){
 if(!verifySession(req.cookies.get(getCookieName())?.value))return NextResponse.json({error:'Unauthorized'},{status:401});
 try{
  await ensureSchema();
  const b=await req.json();
  if(!b.licenseId||!b.deviceId)return NextResponse.json({error:'Lisensi dan perangkat wajib dipilih.'},{status:400});
  const rows=await database()`DELETE FROM license_devices WHERE license_id=${String(b.licenseId)} AND device_id=${String(b.deviceId)} RETURNING device_name`;
  if(!rows.length)return NextResponse.json({error:'Perangkat tidak ditemukan.'},{status:404});
  await database()`INSERT INTO admin_audit_logs(actor,action,detail) VALUES('admin','license.device.release',${JSON.stringify({licenseId:b.licenseId,deviceName:rows[0].device_name})}::jsonb)`;
  return NextResponse.json({success:true,message:'Perangkat berhasil dilepas dari lisensi.'});
 }catch(e:any){return NextResponse.json({error:e.message},{status:400});}
}
