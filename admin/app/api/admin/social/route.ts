import { NextRequest,NextResponse } from 'next/server';
import { getCookieName,verifySession } from '@/lib/server';
import { proxySocialAdminRequest } from '@/lib/social-supabase';
export const runtime='nodejs';
export const dynamic='force-dynamic';
function authorized(req:NextRequest){return verifySession(req.cookies.get(getCookieName())?.value);}
export async function GET(req:NextRequest){
 if(!authorized(req))return NextResponse.json({error:'Unauthorized'},{status:401});
 return proxySocialAdminRequest(req);
}
export async function PATCH(req:NextRequest){
 if(!authorized(req))return NextResponse.json({error:'Unauthorized'},{status:401});
 return proxySocialAdminRequest(req);
}
