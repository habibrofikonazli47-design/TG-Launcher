import {NextRequest,NextResponse} from 'next/server';
import {database,ensureSchema} from '@/lib/db';
import {getCookieName,verifySession} from '@/lib/server';
export const runtime='nodejs';
export const dynamic='force-dynamic';

function authorized(req:NextRequest){return verifySession(req.cookies.get(getCookieName())?.value);}

export async function GET(req:NextRequest){
 if(!authorized(req))return NextResponse.json({error:'Unauthorized'},{status:401});
 try{
  await ensureSchema();
  const sql=database();
  const [sizeRows,statRows,connectionRows,tableRows,cleanupRows,versionRows]=await Promise.all([
   sql`SELECT pg_size_pretty(pg_database_size(current_database())) AS database_size`,
   sql`SELECT COALESCE(round(100.0*blks_hit/NULLIF(blks_hit+blks_read,0),2),0) AS cache_hit_ratio FROM pg_stat_database WHERE datname=current_database()`,
   sql`SELECT count(*)::int AS connections FROM pg_stat_activity WHERE datname=current_database()`,
   sql`SELECT relname AS table_name, n_live_tup::bigint AS estimated_rows, pg_size_pretty(pg_total_relation_size(relid)) AS total_size FROM pg_stat_user_tables ORDER BY pg_total_relation_size(relid) DESC`,
   sql`SELECT count(*)::int AS candidate_count FROM launcher_ai_usage WHERE created_at < now() - interval '90 days'`,
   sql`SELECT current_setting('server_version') AS server_version`
  ]);
  const estimatedRows=(tableRows||[]).reduce((sum:number,row:any)=>sum+Number(row.estimated_rows||0),0);
  return NextResponse.json({
   configured:true,provider:'Neon PostgreSQL',connected:true,
   databaseSize:sizeRows[0]?.database_size||null,
   cacheHitRatio:Number(statRows[0]?.cache_hit_ratio??0),
   connections:Number(connectionRows[0]?.connections??0),
   estimatedRows,
   serverVersion:versionRows[0]?.server_version||null,
   cleanupCandidateCount:Number(cleanupRows[0]?.candidate_count??0),
   tables:(tableRows||[]).map((row:any)=>({tableName:row.table_name,estimatedRows:Number(row.estimated_rows||0),size:row.total_size}))
  },{headers:{'Cache-Control':'no-store'}});
 }catch(e:any){
  return NextResponse.json({configured:!!process.env.DATABASE_URL,provider:'Neon PostgreSQL',connected:false,error:e.message},{status:503,headers:{'Cache-Control':'no-store'}});
 }
}

export async function POST(req:NextRequest){
 if(!authorized(req))return NextResponse.json({error:'Unauthorized'},{status:401});
 try{
  const body=await req.json();
  if(body.action!=='cleanup_ai_usage'||body.confirmText!=='BERSIHKAN LOG AI')return NextResponse.json({error:'Aksi atau konfirmasi pembersihan tidak valid.'},{status:400});
  await ensureSchema();
  const sql=database();
  const result=await sql`WITH removed AS (DELETE FROM launcher_ai_usage WHERE created_at < now() - interval '90 days' RETURNING id) SELECT count(*)::int AS deleted_count FROM removed`;
  const deletedCount=Number(result[0]?.deleted_count??0);
  await sql`INSERT INTO admin_audit_logs(actor,action,detail) VALUES('admin','database.cleanup_ai_usage',${JSON.stringify({deletedCount,retentionDays:90})}::jsonb)`;
  return NextResponse.json({success:true,deletedCount,message:`Pembersihan selesai. ${deletedCount} log AI lama dihapus. Data akun, lisensi, dan pengaturan tetap aman.`});
 }catch(e:any){return NextResponse.json({error:e.message||'Pembersihan database gagal.'},{status:503});}
}
