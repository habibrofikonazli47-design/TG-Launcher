import { NextRequest, NextResponse } from 'next/server';
import { database, ensureSchema } from '@/lib/db';
import { getCookieName, verifySession } from '@/lib/server';
export const runtime='nodejs';
export async function GET(req:NextRequest){
 if(!verifySession(req.cookies.get(getCookieName())?.value))return NextResponse.json({error:'Unauthorized'},{status:401});
 try {await ensureSchema();const q=new URL(req.url).searchParams.get('q')||'';const sql=database();
 const rows=q?await sql`SELECT id,email,display_name,role,plan,status,expires_at,created_at FROM profiles WHERE email ILIKE ${'%'+q+'%'} OR display_name ILIKE ${'%'+q+'%'} ORDER BY created_at DESC LIMIT 200`:await sql`SELECT id,email,display_name,role,plan,status,expires_at,created_at FROM profiles ORDER BY created_at DESC LIMIT 200`;
 return NextResponse.json(rows);
 }catch(e:any){return NextResponse.json({error:e.message},{status:503});}
}
export async function PATCH(req:NextRequest){
 if(!verifySession(req.cookies.get(getCookieName())?.value))return NextResponse.json({error:'Unauthorized'},{status:401});
 try {await ensureSchema();const b=await req.json();if(!b.id||!['active','blocked'].includes(b.status)||!['free','premium'].includes(b.plan))return NextResponse.json({error:'Data perubahan tidak valid'},{status:400});
 const rows=await database()`UPDATE profiles SET status=${b.status},plan=${b.plan},expires_at=${b.expiresAt||null},updated_at=now() WHERE id=${String(b.id)} RETURNING id,email,plan,status,expires_at`;
 if(!rows.length)return NextResponse.json({error:'Pengguna tidak ditemukan'},{status:404});return NextResponse.json(rows[0]);
 }catch(e:any){return NextResponse.json({error:e.message},{status:400});}
}
