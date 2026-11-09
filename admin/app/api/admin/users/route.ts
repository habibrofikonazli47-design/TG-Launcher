import { NextRequest, NextResponse } from 'next/server';
import { database, ensureSchema } from '@/lib/db';
import { getCookieName, verifySession } from '@/lib/server';
export const runtime='nodejs';
export async function GET(req:NextRequest){
 if(!verifySession(req.cookies.get(getCookieName())?.value))return NextResponse.json({error:'Unauthorized'},{status:401});
 try {await ensureSchema();const q=new URL(req.url).searchParams.get('q')||'';const sql=database();
 const rows=q?await sql`SELECT id,email,display_name,role,plan,status,expires_at,feature_overrides,limit_overrides,created_at FROM profiles WHERE email ILIKE ${'%'+q+'%'} OR display_name ILIKE ${'%'+q+'%'} ORDER BY created_at DESC LIMIT 200`:await sql`SELECT id,email,display_name,role,plan,status,expires_at,feature_overrides,limit_overrides,created_at FROM profiles ORDER BY created_at DESC LIMIT 200`;
 return NextResponse.json(rows);
 }catch(e:any){return NextResponse.json({error:e.message},{status:503});}
}
export async function PATCH(req:NextRequest){
 if(!verifySession(req.cookies.get(getCookieName())?.value))return NextResponse.json({error:'Unauthorized'},{status:401});
 try {await ensureSchema();const b=await req.json();if(!b.id||!['active','blocked'].includes(b.status)||!['free','premium'].includes(b.plan))return NextResponse.json({error:'Data perubahan tidak valid'},{status:400});
 const featureOverrides=b.featureOverrides===null?null:b.featureOverrides;
 const limitOverrides=b.limitOverrides??{};
 const allowedFeatures=['base_launcher','screen_recorder','voice_rooms','unlimited_instances','custom_video_hero','premium_badge','snapshot_beta_versions','unlimited_accounts','turbo_downloader'];
 if(featureOverrides!==null&&(!Array.isArray(featureOverrides)||!featureOverrides.every((x:any)=>allowedFeatures.includes(x))))return NextResponse.json({error:'Daftar fitur khusus tidak valid.'},{status:400});
 const allowedLimits=['instances','accounts','ai_daily','download_workers'];
 if(!limitOverrides||typeof limitOverrides!=='object'||Array.isArray(limitOverrides)||Object.entries(limitOverrides).some(([k,v]:any)=>!allowedLimits.includes(k)||typeof v!=='number'||!Number.isFinite(v)||v< -1))return NextResponse.json({error:'Batas khusus tidak valid. Gunakan angka dan -1 untuk tanpa batas.'},{status:400});
 const rows=await database()`UPDATE profiles SET status=${b.status},plan=${b.plan},expires_at=${b.expiresAt||null},feature_overrides=${featureOverrides===null?null:JSON.stringify(featureOverrides)}::jsonb,limit_overrides=${JSON.stringify(limitOverrides)}::jsonb,license_id=NULL,updated_at=now() WHERE id=${String(b.id)} RETURNING id,email,plan,status,expires_at,feature_overrides,limit_overrides`;
 if(!rows.length)return NextResponse.json({error:'Pengguna tidak ditemukan'},{status:404});
 await database()`INSERT INTO admin_audit_logs(actor,action,detail) VALUES('admin','user.entitlements.update',${JSON.stringify({userId:String(b.id),plan:b.plan,expiresAt:b.expiresAt||null,featureOverrides,limitOverrides})}::jsonb)`;
 return NextResponse.json(rows[0]);
 }catch(e:any){return NextResponse.json({error:e.message},{status:400});}
}
