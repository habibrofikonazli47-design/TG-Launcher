import {NextRequest,NextResponse} from 'next/server';
import {database,ensureSchema} from '@/lib/db';
import {getCookieName,verifySession} from '@/lib/server';
export const runtime='nodejs';
export async function POST(req:NextRequest){if(!verifySession(req.cookies.get(getCookieName())?.value))return NextResponse.json({error:'Unauthorized'},{status:401});try{await ensureSchema();const rows=await database()`SELECT current_database() AS database, now() AS server_time`;return NextResponse.json({ok:true,message:'Koneksi Neon PostgreSQL berhasil.',database:rows[0].database,serverTime:rows[0].server_time});}catch(e:any){return NextResponse.json({ok:false,error:e.message},{status:503});}}
