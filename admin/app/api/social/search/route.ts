import { NextRequest, NextResponse } from 'next/server';
import { isSocialFeatureEnabled } from '@/lib/social-db';
import { proxySocialRequest } from '@/lib/social-supabase';
export const runtime='nodejs';
export const dynamic='force-dynamic';
export async function GET(req:NextRequest){
 if(!(await isSocialFeatureEnabled('friendsEnabled'))) return NextResponse.json({error:'Pencarian sosial dinonaktifkan oleh admin TG.'},{status:403});
 return proxySocialRequest(req,'search');
}
