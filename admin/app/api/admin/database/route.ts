import {NextRequest,NextResponse} from 'next/server';
import {database,ensureSchema} from '@/lib/db';
import {getCookieName,verifySession} from '@/lib/server';
export const runtime='nodejs';
export async function GET(req:NextRequest){if(!verifySession(req.cookies.get(getCookieName())?.value))return NextResponse.json({error:'Unauthorized'},{status:401});try{await ensureSchema();await database()`SELECT 1`;return NextResponse.json({configured:true,provider:'Neon PostgreSQL',connected:true});}catch(e:any){return NextResponse.json({configured:!!process.env.DATABASE_URL,provider:'Neon PostgreSQL',connected:false,error:e.message},{status:503});}}
