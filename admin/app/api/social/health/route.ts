import { NextResponse } from 'next/server';
export const runtime='nodejs';
export const dynamic='force-dynamic';
export async function GET(){
  const endpoint=process.env.TG_SOCIAL_SUPABASE_FUNCTION_URL?.trim();
  const key=process.env.TG_SOCIAL_SUPABASE_PUBLISHABLE_KEY?.trim();
  if(!endpoint||!key)return NextResponse.json({success:false,service:'TG Social & Voice',database:'unavailable',error:'Koneksi Supabase Social belum dikonfigurasi.'},{status:503,headers:{'Cache-Control':'no-store'}});
  try{
    const url=new URL(endpoint);url.searchParams.set('op','health');
    const response=await fetch(url,{headers:{apikey:key,accept:'application/json'},cache:'no-store',signal:AbortSignal.timeout(10000)});
    const payload=await response.json().catch(()=>({}));
    if(!response.ok)return NextResponse.json({success:false,service:'TG Social & Voice',database:'unavailable',error:payload.error||'Database Social belum siap.'},{status:503,headers:{'Cache-Control':'no-store'}});
    return NextResponse.json({...payload,service:'TG Social & Voice'},{headers:{'Cache-Control':'no-store'}});
  }catch(error){
    console.error('[tg-social-health]',error);
    return NextResponse.json({success:false,service:'TG Social & Voice',database:'unavailable',error:'Layanan sosial belum siap dikonfigurasi.'},{status:503,headers:{'Cache-Control':'no-store'}});
  }
}
