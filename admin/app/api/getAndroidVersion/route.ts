export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

import { database, ensureSchema } from '@/lib/db';

export async function GET() {
  try {
    const response = await fetch('https://api.github.com/repos/habibrofikonazli47-design/TG-Launcher/releases/latest', {
      headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'TG-Launcher-Updater' },
      cache: 'no-store',
    });
    if (!response.ok) throw new Error('GitHub Releases tidak dapat diakses.');
    const release = await response.json();
    const asset = (release.assets || []).find((item: any) => item.name === 'app-release.apk')
      || (release.assets || []).find((item: any) => item.name.toLowerCase().endsWith('.apk'));
    if (!asset) return Response.json({ error: 'APK release belum tersedia.' }, { status: 404 });
    const tag = String(release.tag_name || '').replace(/^v/, '');
    let updatePolicy = { forceUpdate: false, minimumSupportedVersion: '1.0.0' };
    try {
      await ensureSchema();
      const sql = database();
      const rows = await sql`SELECT value FROM app_settings WHERE key='updates' LIMIT 1`;
      if (rows.length && rows[0].value) updatePolicy = { ...updatePolicy, ...rows[0].value };
    } catch (policyError) {
      console.error('[getAndroidVersion] update policy unavailable', policyError);
    }
    let metadata: any = null;
    const metadataAsset = (release.assets || []).find((item: any) => item.name === 'output-metadata.json');
    if (metadataAsset?.browser_download_url) {
      const metadataResponse = await fetch(metadataAsset.browser_download_url, { cache: 'no-store' });
      if (metadataResponse.ok) metadata = await metadataResponse.json();
    }
    const element = metadata?.elements?.[0] || {};
    const tagMatch = tag.match(/(?:tg|build)?(\d+)$/i);
    const fallbackCode = tagMatch ? 17 + Number(tagMatch[1]) : 18;
    const code = Number(element.versionCode) || fallbackCode;
    const version = String(element.versionName || tag || release.name || '1.1.6');
    return Response.json({
      code: Number.isFinite(code) ? code : 18,
      version,
      created_at: release.published_at || release.created_at || '',
      files: [{ uri: asset.browser_download_url, file_name: asset.name, size: asset.size }],
      default_body: { markdown: release.body || 'Pembaruan TG Launcher tersedia.' },
    }, { headers: { 'Cache-Control': 'no-store, max-age=0' } });
  } catch (error: any) {
    return Response.json({ error: error?.message || 'Gagal memeriksa pembaruan.' }, { status: 503 });
  }
}
