import { NextResponse } from 'next/server';
import { database, ensureSchema } from '@/lib/db';
export const runtime='nodejs';
export const dynamic='force-dynamic';
const defaults:any={
 general:{maintenanceMode:false,maintenanceMessage:'',minimumAppVersion:'1.0.0',forceUpdate:false,allowRegistration:true,defaultPlan:'free',supportUrl:'',websiteUrl:''},
 updates:{autoUpdateCheck:true,stableChannel:true,betaChannel:false,minimumSupportedVersion:'1.0.0',releaseNotes:''},
 appearance:{announcementEnabled:false,announcementTitle:'',announcementContent:'',announcementImageUrl:'',announcementLinkUrl:'',announcementTarget:'all',bannerEnabled:true,bannerImageUrl:'',bannerLinkUrl:''},
 features:{game:true,runtimes:true,mods:true,renderer:true,ai:true,accounts:true,social:true}
};
export async function GET(){try{await ensureSchema();const sql=database();const rows=await sql`SELECT key,value FROM app_settings WHERE key IN ('general','updates','appearance','features')`;const config={...defaults};for(const row of rows)config[row.key]=row.value;return NextResponse.json(config,{headers:{'Cache-Control':'no-store'}});}catch{return NextResponse.json(defaults,{headers:{'Cache-Control':'no-store'}});}}
