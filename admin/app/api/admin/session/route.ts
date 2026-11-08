import { NextRequest, NextResponse } from 'next/server';
import { getCookieName, verifySession } from '@/lib/server';
export async function GET(request: NextRequest) { const ok = verifySession(request.cookies.get(getCookieName())?.value); return NextResponse.json({authenticated:ok, email:ok?process.env.ADMIN_EMAIL:null}); }
export async function DELETE() { const r=NextResponse.json({ok:true}); r.cookies.set({name:getCookieName(),value:'',httpOnly:true,secure:process.env.NODE_ENV==='production',sameSite:'strict',path:'/',maxAge:0}); return r; }
