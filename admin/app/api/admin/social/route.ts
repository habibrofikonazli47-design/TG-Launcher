import { NextRequest,NextResponse } from 'next/server';
import { sign as signEd25519,randomUUID } from 'node:crypto';
import { getCookieName,verifySession } from '@/lib/server';
import { proxySocialAdminRequest } from '@/lib/social-supabase';
export const runtime='nodejs';
export const dynamic='force-dynamic';
function createAdminAssertion(){
 const pem=process.env.TG_SOCIAL_ADMIN_SIGNING_PRIVATE_KEY?.replace(/\\n/g,'\n');
 if(!pem)return null;
 const now=Math.floor(Date.now()/1000);
 const header=Buffer.from(JSON.stringify({alg:'EdDSA',typ:'JWT'})).toString('base64url');
 const payload=Buffer.from(JSON.stringify({sub:'tg-admin',aud:'tg-social-admin',iat:now,exp:now+45,jti:randomUUID()})).toString('base64url');
 const input=header+'.'+payload;
 const signature=signEd25519(null,Buffer.from(input),pem).toString('base64url');
 return input+'.'+signature;
}
function getAssertion(req:NextRequest){
 if(!verifySession(req.cookies.get(getCookieName())?.value))return null;
 return createAdminAssertion();
}
export async function GET(req:NextRequest){
 const assertion=getAssertion(req);
 if(!assertion)return NextResponse.json({error:'Unauthorized atau kunci penghubung admin belum dikonfigurasi.'},{status:401});
 return proxySocialAdminRequest(req,assertion);
}
export async function PATCH(req:NextRequest){
 const assertion=getAssertion(req);
 if(!assertion)return NextResponse.json({error:'Unauthorized atau kunci penghubung admin belum dikonfigurasi.'},{status:401});
 return proxySocialAdminRequest(req,assertion);
}
