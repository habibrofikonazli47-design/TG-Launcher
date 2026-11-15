import { NextRequest, NextResponse } from 'next/server';
import { getCookieName, verifySession } from '@/lib/server';
import { initializeDriveStorage } from '@/lib/google-drive';

export const runtime = 'nodejs';

export async function POST(request: NextRequest) {
  if (!verifySession(request.cookies.get(getCookieName())?.value)) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }
  try {
    const storage = await initializeDriveStorage();
    return NextResponse.json({
      success: true,
      message: 'Folder penyimpanan TG Launcher berhasil disiapkan di Google Drive.',
      storage,
    });
  } catch (error: any) {
    console.error('[admin/storage/drive/initialize]', error);
    return NextResponse.json({ error: error.message || 'Gagal menyiapkan Google Drive.' }, { status: 503 });
  }
}
