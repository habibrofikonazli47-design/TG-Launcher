import { NextRequest, NextResponse } from 'next/server';
import { isSocialFeatureEnabled } from '@/lib/social-db';
import { proxySocialRequest } from '@/lib/social-supabase';
export const runtime='nodejs';
export const dynamic='force-dynamic';
async function enabled(){return await isSocialFeatureEnabled('friendsEnabled');}
export async function GET(req:NextRequest){
 if(!(await enabled())) return NextResponse.json({error:'Chat dinonaktifkan oleh admin TG.'},{status:403});
 return proxySocialRequest(req,'messages');
}
export async function POST(req:NextRequest){
 if(!(await enabled())) return NextResponse.json({error:'Chat dinonaktifkan oleh admin TG.'},{status:403});
 return proxySocialRequest(req,'messages');
}
