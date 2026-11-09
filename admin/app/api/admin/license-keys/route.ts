import { NextRequest,NextResponse } from 'next/server';
import { randomBytes } from 'crypto';
import { database,ensureSchema } from '@/lib/db';
import { getCookieName,verifySession } from '@/lib/server';
export const runtime='nodejs';
function makeKey(){const raw=randomBytes(10).toString('hex').toUpperCase();return raw.match(/.{1,5}/g)!.join('-');}
export async function GET(req:NextRequest){
 if(!verifySession(req.cookies.get(getCookieName())?.value))return NextResponse.json({error:'Unauthorized'},{status:401});
 try{await ensureSchema();return NextResponse.json(await database()`SELECT id,license_key,plan,status,max_uses,used_count,expires_at,duration_days,max_devices,user_id,activated_at,created_at,(SELECT count(*)::int FROM license_devices d WHERE d.license_id=license_keys.id) AS device_count FROM license_keys ORDER BY created_at DESC LIMIT 300`);}
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
 try{await ensureSchema();const b=await req.json();if(!b.id||!['active','revoked'].includes(b.status))return NextResponse.json({error:'Status lisensi tidak valid.'},{status:400});
 const rows=await database()`UPDATE license_keys SET status=${b.status} WHERE id=${String(b.id)} RETURNING id,license_key,status`;
 if(!rows.length)return NextResponse.json({error:'Kode tidak ditemukan.'},{status:404});
 await database()`INSERT INTO admin_audit_logs(actor,action,detail) VALUES('admin','license.status',${JSON.stringify({id:b.id,status:b.status})}::jsonb)`;
 return NextResponse.json(rows[0]);}catch(e:any){return NextResponse.json({error:e.message},{status:400});}
}
