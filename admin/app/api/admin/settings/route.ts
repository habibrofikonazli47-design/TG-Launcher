import { NextRequest, NextResponse } from 'next/server';
import { database, ensureSchema } from '@/lib/db';
import { getCookieName, verifySession } from '@/lib/server';
import { encryptLiveKitSecret } from '@/lib/livekit-config';
export const runtime='nodejs';
const defaults:Record<string,any>={
  general:{maintenanceMode:false,maintenanceMessage:'TG Launcher sedang dalam pemeliharaan.',minimumAppVersion:'1.0.0',forceUpdate:false,allowRegistration:true,defaultPlan:'free',supportUrl:'',websiteUrl:'',premiumPurchaseUrl:''},
  game:{minecraftReleaseEnabled:true,minecraftSnapshotEnabled:true,showOldVersions:true,defaultMemoryMb:2048,maxMemoryMb:8192,downloadMirror:'official',allowCustomInstances:true},
  runtimes:{java8Enabled:true,java17Enabled:true,java21Enabled:true,java25Enabled:true,autoSelectJava:true,preferredRuntime:'auto',runtimeMirror:'official'},
  mods:{modrinthEnabled:true,curseforgeEnabled:true,fabricEnabled:true,forgeEnabled:true,quiltEnabled:true,neoForgeEnabled:true,allowManualImport:true},
  renderer:{virglEnabled:true,gl4esEnabled:true,mesaEnabled:true,zinkEnabled:true,defaultRenderer:'auto',experimentalRenderers:false},
  ai:{assistantEnabled:true,crashAnalyzerEnabled:true,freeDailyQuota:5,premiumDailyQuota:-1,model:'default',maxTokens:2048,customApiKeyAllowed:true},
  accounts:{offlineLoginEnabled:true,microsoftLoginEnabled:true,elybyLoginEnabled:true,registrationEnabled:true,otpEnabled:true,otpCooldownSeconds:60},
  social:{friendsEnabled:true,voiceEnabled:true,livekitEnabled:false,uploadsEnabled:true,maximumUploadMb:20,livekitServerUrl:'',livekitApiKey:'',livekitApiKeyConfigured:false,livekitApiSecret:'',livekitApiSecretConfigured:false},
  updates:{releaseEnabled:false,autoUpdateCheck:true,stableChannel:true,betaChannel:false,forceUpdate:false,minimumSupportedVersion:'1.0.0',releaseNotes:''},
  security:{apiKeyRequired:true,rateLimitPerMinute:60,blockRootedDevices:false,sessionHours:8,allowUnknownClients:false},
  appearance:{backgroundImageUrl:'',backgroundOverlayAlpha:65,announcementEnabled:false,announcementTitle:'',announcementContent:'',announcementImageUrl:'',announcementLinkUrl:'',announcementTarget:'all',bannerEnabled:true,bannerImageUrl:'',bannerLinkUrl:''}
};
export async function GET(req:NextRequest){
 if(!verifySession(req.cookies.get(getCookieName())?.value)) return NextResponse.json({error:'Unauthorized'},{status:401});
 try{await ensureSchema();const sql=database();const rows=await sql`SELECT key,value,updated_at FROM app_settings ORDER BY key`;const settings={...defaults};for(const row of rows){const base=defaults[row.key];settings[row.key]=base&&row.value&&typeof base==='object'&&typeof row.value==='object'?{...base,...row.value}:row.value;}const social=settings.social||{};const encrypted=typeof social.livekitApiSecretEncrypted==='string'?social.livekitApiSecretEncrypted:'';const encryptedKey=typeof social.livekitApiKeyEncrypted==='string'?social.livekitApiKeyEncrypted:'';settings.social={...social,livekitApiKey:'',livekitApiKeyConfigured:Boolean(encryptedKey),livekitApiSecret:'',livekitApiSecretConfigured:Boolean(encrypted)};delete settings.social.livekitApiSecretEncrypted;delete settings.social.livekitApiKeyEncrypted;return NextResponse.json({settings,defaults});}
 catch(e:any){return NextResponse.json({error:e.message},{status:503});}
}
export async function PUT(req:NextRequest){
 if(!verifySession(req.cookies.get(getCookieName())?.value)) return NextResponse.json({error:'Unauthorized'},{status:401});
 try{const body=await req.json();const category=String(body.category||'');const value=body.value;if(!Object.prototype.hasOwnProperty.call(defaults,category)||!value||typeof value!=='object'||Array.isArray(value))return NextResponse.json({error:'Kategori atau konfigurasi tidak valid.'},{status:400});
 const allowed=Object.keys(defaults[category]);const clean:any={};for(const key of allowed){if(key==='livekitApiSecretConfigured'||key==='livekitApiKeyConfigured')continue;if((key==='livekitApiSecret'||key==='livekitApiKey')&&category==='social')continue;if(Object.prototype.hasOwnProperty.call(value,key))clean[key]=value[key];}
 await ensureSchema();const sql=database();if(category==='social'){const existing=await sql`SELECT value FROM app_settings WHERE key='social' LIMIT 1`;const previous=(existing[0]?.value||{}) as Record<string,any>;const secret=typeof value.livekitApiSecret==='string'?value.livekitApiSecret.trim():'';const apiKey=typeof value.livekitApiKey==='string'?value.livekitApiKey.trim():'';const serverUrl=String(clean.livekitServerUrl||'').trim();if(serverUrl&&!serverUrl.startsWith('wss://'))return NextResponse.json({error:'LiveKit Server URL harus menggunakan wss://.'},{status:400});clean.livekitApiSecretEncrypted=secret?encryptLiveKitSecret(secret):previous.livekitApiSecretEncrypted||'';clean.livekitApiKeyEncrypted=apiKey?encryptLiveKitSecret(apiKey):previous.livekitApiKeyEncrypted||'';}await sql`INSERT INTO app_settings(key,value,updated_at,updated_by) VALUES(${category},${JSON.stringify(clean)}::jsonb,now(),'admin') ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value,updated_at=now(),updated_by='admin'`;await sql`INSERT INTO admin_audit_logs(actor,action,detail) VALUES('admin','settings.update',${JSON.stringify({category,fields:Object.keys(clean)})}::jsonb)`;const responseValue={...clean};delete responseValue.livekitApiSecretEncrypted;delete responseValue.livekitApiKeyEncrypted;return NextResponse.json({success:true,message:'Pengaturan '+category+' berhasil disimpan.',value:responseValue});}
 catch(e:any){return NextResponse.json({error:e.message},{status:503});}
}
