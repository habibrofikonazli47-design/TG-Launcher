export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

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
    const codeMatch = tag.match(/(?:tg|build)?(\d+)$/i);
    const code = codeMatch ? 17 + Number(codeMatch[1]) : 18;
    return Response.json({
      code: Number.isFinite(code) ? code : 18,
      version: tag || release.name || '1.1.6-tg1',
      created_at: release.published_at || release.created_at || '',
      files: [{ uri: asset.browser_download_url, file_name: asset.name, size: asset.size }],
      default_body: { markdown: release.body || 'Pembaruan TG Launcher tersedia.' },
    }, { headers: { 'Cache-Control': 'no-store, max-age=0' } });
  } catch (error: any) {
    return Response.json({ error: error?.message || 'Gagal memeriksa pembaruan.' }, { status: 503 });
  }
}
