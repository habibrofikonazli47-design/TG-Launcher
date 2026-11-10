import { NextRequest, NextResponse } from 'next/server';
import { isSocialFeatureEnabled } from '@/lib/social-db';
import { proxySocialRequest } from '@/lib/social-supabase';
export const runtime='nodejs';
export const dynamic='force-dynamic';
export async function GET(req:NextRequest){
 if(!(await isSocialFeatureEnabled('friendsEnabled'))) return NextResponse.json({error:'Fitur pertemanan dinonaktifkan oleh admin TG.'},{status:403});
 return proxySocialRequest(req,'friends');
}
export async function POST(req:NextRequest){
 if(!(await isSocialFeatureEnabled('friendsEnabled'))) return NextResponse.json({error:'Fitur pertemanan dinonaktifkan oleh admin TG.'},{status:403});
 return proxySocialRequest(req,'friends');
}
