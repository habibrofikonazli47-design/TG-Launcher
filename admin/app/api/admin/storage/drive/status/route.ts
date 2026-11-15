import { NextRequest, NextResponse } from 'next/server';
import { getCookieName, verifySession } from '@/lib/server';
import { getDriveStorageStatus } from '@/lib/google-drive';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest) {
  if (!verifySession(request.cookies.get(getCookieName())?.value)) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }
  try {
    return NextResponse.json(await getDriveStorageStatus(), {
      headers: { 'Cache-Control': 'no-store, max-age=0' },
    });
  } catch (error: any) {
    console.error('[admin/storage/drive/status]', error);
    return NextResponse.json({ configured: true, connected: false, error: error.message || 'Google Drive tidak dapat dihubungkan.' }, { status: 503 });
  }
}
