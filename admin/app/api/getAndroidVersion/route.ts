import { database, ensureSchema } from '@/lib/db';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    await ensureSchema();
    const sql = database();
    const rows = await sql`SELECT key,value FROM app_settings WHERE key IN ('updates','general')`;
    const settings = Object.fromEntries(rows.map((row: any) => [row.key, row.value || {}]));
    const updates = settings.updates || {};
    const general = settings.general || {};
    const releaseEnabled = updates.releaseEnabled === true;
    const updatePolicy = {
      forceUpdate: updates.forceUpdate === true || general.forceUpdate === true,
      minimumSupportedVersion: updates.forceUpdate === true
        ? updates.minimumSupportedVersion || '1.0.0'
        : general.forceUpdate === true
          ? general.minimumAppVersion || updates.minimumSupportedVersion || '1.0.0'
          : updates.minimumSupportedVersion || general.minimumAppVersion || '1.0.0',
    };

    // Do not distribute an older APK while a stable signing key/release is not approved.
    const autoUpdateCheck = updates.autoUpdateCheck !== false;
    const stableChannel = updates.stableChannel !== false;
    const betaChannel = updates.betaChannel === true;
    const noUpdate = (message: string) => Response.json({
      code: 0,
      version: '0.0.0',
      force_update: false,
      minimum_supported_version: String(updatePolicy.minimumSupportedVersion || '1.0.0'),
      update_available: false,
      created_at: '',
      files: [],
      default_body: { markdown: message },
    }, { headers: { 'Cache-Control': 'no-store, max-age=0' } });

    if (!releaseEnabled) {
      return noUpdate('Distribusi pembaruan TG Launcher belum diaktifkan oleh administrator.');
    }
    if (!autoUpdateCheck && !updatePolicy.forceUpdate) {
      return noUpdate('Pemeriksaan pembaruan otomatis dinonaktifkan oleh administrator.');
    }
    if (!stableChannel && !betaChannel) {
      return noUpdate('Kanal pembaruan TG Launcher sedang dinonaktifkan.');
    }

    // Fetch the full release page: GitHub's release ordering is not guaranteed
    // to put the newest beta inside the first 20 entries.
    const releasesUrl = 'https://api.github.com/repos/habibrofikonazli47-design/TG-Launcher/releases?per_page=100';
    const response = await fetch(releasesUrl, {
      headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'TG-Launcher-Updater' },
      cache: 'no-store',
    });
    if (!response.ok) throw new Error('GitHub Releases tidak dapat diakses.');
    const releasePayload = await response.json();
    const release = Array.isArray(releasePayload)
      ? releasePayload
          .filter((item: any) => item.prerelease === true ? betaChannel : stableChannel)
          .filter((item: any) => (item.assets || []).some((asset: any) => /^TG-Launcher-.*\\.apk$/i.test(asset.name)))
          .sort((a: any, b: any) =>
            Date.parse(b.published_at || b.created_at || '1970-01-01') -
            Date.parse(a.published_at || a.created_at || '1970-01-01')
          )[0]
      : releasePayload;
    if (!release) return noUpdate('Belum ada rilis pada kanal pembaruan yang dipilih.');
    const asset = (release.assets || []).find((item: any) => /^TG-Launcher-.*\.apk$/i.test(item.name));
    if (!asset) return Response.json({ error: 'APK release belum tersedia.' }, { status: 404 });
    const tag = String(release.tag_name || '').replace(/^v/, '');
    const versionTag = tag.replace(/^tg-launcher-/i, '');
    let metadata: any = null;
    const metadataAsset = (release.assets || []).find((item: any) => item.name === 'output-metadata.json');
    if (metadataAsset?.browser_download_url) {
      const metadataResponse = await fetch(metadataAsset.browser_download_url, { cache: 'no-store' });
      if (metadataResponse.ok) metadata = await metadataResponse.json();
    }
    const element = metadata?.elements?.[0] || {};
    const tagMatch = versionTag.match(/(?:tg|build)?(\d+)$/i);
    const fallbackCode = tagMatch ? 17 + Number(tagMatch[1]) : 18;
    const code = Number(element.versionCode) || fallbackCode;
    const version = String(element.versionName || versionTag || String(release.name || '').replace(/^TG Launcher\s*/i, '') || '1.1.6');
    return Response.json({
      code: Number.isFinite(code) ? code : 18,
      version,
      force_update: updatePolicy.forceUpdate === true,
      update_available: true,
      minimum_supported_version: String(updatePolicy.minimumSupportedVersion || '1.0.0'),
      created_at: release.published_at || release.created_at || '',
      files: [{ uri: asset.browser_download_url, file_name: asset.name, size: asset.size }],
      default_body: { markdown: release.body || 'Pembaruan TG Launcher tersedia.' },
    }, { headers: { 'Cache-Control': 'no-store, max-age=0' } });
  } catch (error: any) {
    return Response.json({ error: error?.message || 'Gagal memeriksa pembaruan.' }, { status: 503 });
  }
}
