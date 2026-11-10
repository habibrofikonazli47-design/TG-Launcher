import { NextRequest, NextResponse } from 'next/server';
import { getCookieName, verifySession } from '@/lib/server';
export const runtime='nodejs';
export const dynamic='force-dynamic';
export async function GET(req:NextRequest){
  const session=req.cookies.get(getCookieName())?.value;
  if(!verifySession(session)) return new NextResponse(null,{status:401,headers:{'Cache-Control':'no-store'}});
  return new NextResponse(null,{status:204,headers:{'Cache-Control':'no-store'}});
}
