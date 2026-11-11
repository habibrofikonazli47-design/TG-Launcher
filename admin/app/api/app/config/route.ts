import { NextResponse } from 'next/server';
import { database, ensureSchema } from '@/lib/db';
export const runtime='nodejs';
export const dynamic='force-dynamic';
const defaults:any={
 general:{maintenanceMode:false,maintenanceMessage:'',minimumAppVersion:'1.0.0',forceUpdate:false,allowRegistration:true,defaultPlan:'free',supportUrl:'',websiteUrl:'',premiumPurchaseUrl:'',premiumMonthlyPurchaseUrl:'',premiumYearlyPurchaseUrl:'',premiumLifetimePurchaseUrl:''},
 game:{minecraftReleaseEnabled:true,minecraftSnapshotEnabled:true,showOldVersions:true,defaultMemoryMb:2048,maxMemoryMb:8192,downloadMirror:'official',allowCustomInstances:true},
 runtimes:{java8Enabled:true,java17Enabled:true,java21Enabled:true,java25Enabled:true,autoSelectJava:true,preferredRuntime:'auto',runtimeMirror:'official'},
 mods:{modrinthEnabled:true,curseforgeEnabled:true,fabricEnabled:true,forgeEnabled:true,quiltEnabled:true,neoForgeEnabled:true,allowManualImport:true},
 renderer:{virglEnabled:true,gl4esEnabled:true,mesaEnabled:true,zinkEnabled:true,defaultRenderer:'auto',experimentalRenderers:false},
 recorder:{screenRecordingEnabled:true},
 ai:{assistantEnabled:true,crashAnalyzerEnabled:true,freeDailyQuota:5,premiumDailyQuota:-1,model:'default',maxTokens:2048,customApiKeyAllowed:true},
 accounts:{offlineLoginEnabled:true,microsoftLoginEnabled:true,elybyLoginEnabled:true,registrationEnabled:true,otpEnabled:true,otpCooldownSeconds:60},
 social:{friendsEnabled:true,voiceEnabled:true,worldInvitesEnabled:false,livekitEnabled:false,uploadsEnabled:true,maximumUploadMb:20},
 updates:{releaseEnabled:false,autoUpdateCheck:true,stableChannel:true,betaChannel:false,forceUpdate:false,minimumSupportedVersion:'1.0.0',releaseNotes:''},
 appearance:{backgroundImageUrl:'',backgroundOverlayAlpha:65,announcementEnabled:false,announcementTitle:'',announcementContent:'',announcementImageUrl:'',announcementLinkUrl:'',announcementTarget:'all',bannerEnabled:true,bannerImageUrl:'',bannerLinkUrl:''}
};
export async function GET(){
 try{
  await ensureSchema();
  const sql=database();
  const rows=await sql`SELECT key,value FROM app_settings WHERE key IN ('general','game','runtimes','mods','renderer','recorder','ai','accounts','social','updates','appearance')`;
  const config:any={...defaults};
  for(const row of rows){if(Object.prototype.hasOwnProperty.call(defaults,row.key)&&row.value&&typeof row.value==='object')config[row.key]={...defaults[row.key],...row.value};}
  // Publish only capabilities that the Android client currently implements.
  // Unsupported legacy flags remain stored for admin visibility but are not advertised to clients.
  config.mods={
   modrinthEnabled:config.mods.modrinthEnabled!==false,
   fabricEnabled:config.mods.fabricEnabled!==false,
   allowManualImport:config.mods.allowManualImport!==false,
  };
  config.runtimes={
   java8Enabled:config.runtimes.java8Enabled!==false,
   java17Enabled:config.runtimes.java17Enabled!==false,
   java21Enabled:config.runtimes.java21Enabled!==false,
   java25Enabled:config.runtimes.java25Enabled!==false,
   autoSelectJava:config.runtimes.autoSelectJava!==false,
   preferredRuntime:config.runtimes.preferredRuntime||'auto',
  };
  config.accounts={
   offlineLoginEnabled:config.accounts.offlineLoginEnabled!==false,
   microsoftLoginEnabled:config.accounts.microsoftLoginEnabled!==false,
   elybyLoginEnabled:config.accounts.elybyLoginEnabled!==false,
   registrationEnabled:config.accounts.registrationEnabled!==false,
  };
  // Never expose encrypted LiveKit credentials or other admin-only Social settings
  // through the public launcher configuration endpoint.
  const social=config.social||{};
  config.social={
   friendsEnabled:social.friendsEnabled!==false,
   voiceEnabled:social.voiceEnabled!==false,
   worldInvitesEnabled:social.worldInvitesEnabled===true,
   livekitEnabled:social.livekitEnabled===true,
   uploadsEnabled:social.uploadsEnabled!==false,
   maximumUploadMb:Number.isFinite(Number(social.maximumUploadMb))?Math.max(1,Math.min(100,Number(social.maximumUploadMb))):20,
  };
  config.features={game:config.game.minecraftReleaseEnabled||config.game.minecraftSnapshotEnabled,runtimes:config.runtimes.java8Enabled||config.runtimes.java17Enabled||config.runtimes.java21Enabled||config.runtimes.java25Enabled,mods:config.mods.modrinthEnabled||config.mods.allowManualImport,renderer:config.renderer.virglEnabled||config.renderer.gl4esEnabled||config.renderer.mesaEnabled||config.renderer.zinkEnabled,recorder:config.recorder.screenRecordingEnabled,ai:config.ai.assistantEnabled,accounts:config.accounts.offlineLoginEnabled||config.accounts.microsoftLoginEnabled||config.accounts.elybyLoginEnabled,social:config.social.friendsEnabled||config.social.voiceEnabled||config.social.worldInvitesEnabled};
  config.branding={name:'TG Launcher',adminName:'TG Launcher Control Center'};
  return NextResponse.json(config,{headers:{'Cache-Control':'no-store, max-age=0'}});
 }catch(error){console.error('[app/config]',error);return NextResponse.json({error:'Konfigurasi TG Launcher sementara tidak tersedia'},{status:503,headers:{'Cache-Control':'no-store'}});}
}