import { NextRequest, NextResponse } from 'next/server';
import { database, ensureSchema } from '@/lib/db';
import { getCookieName, verifySession } from '@/lib/server';
export const runtime='nodejs';
export async function GET(req:NextRequest){
 if(!verifySession(req.cookies.get(getCookieName())?.value)) return NextResponse.json({error:'Unauthorized'},{status:401});
 try { await ensureSchema(); const sql=database();
 const [u,l,p,a]=await Promise.all([
  sql`SELECT count(*)::int AS total, count(*) FILTER (WHERE status='active')::int AS active, count(*) FILTER (WHERE plan='premium' AND status='active' AND (expires_at IS NULL OR expires_at>now()) AND (license_id IS NULL OR EXISTS (SELECT 1 FROM license_keys k WHERE k.id=profiles.license_id AND k.status='active')))::int AS premium FROM profiles`,
  sql`SELECT count(*)::int AS total, count(*) FILTER (WHERE status='active')::int AS active FROM license_keys`,
  sql`SELECT code,name,features,limits FROM plans ORDER BY code`,
  sql`SELECT COALESCE(sum(request_count),0)::int AS total FROM launcher_ai_usage`
 ]);
 return NextResponse.json({users:u[0].total,activeUsers:u[0].active,premiumUsers:u[0].premium,licenses:l[0].total,activeLicenses:l[0].active,plans:p,aiRequests:a[0].total});
 } catch(e:any){return NextResponse.json({error:e.message},{status:503});}
}
