import { NextRequest, NextResponse } from 'next/server';

export async function proxySocialRequest(req: NextRequest, operation: string) {
  const endpoint = process.env.TG_SOCIAL_SUPABASE_FUNCTION_URL?.trim();
  const publishableKey = process.env.TG_SOCIAL_SUPABASE_PUBLISHABLE_KEY?.trim();
  const authorization = req.headers.get('authorization') || '';
  if (!endpoint || !publishableKey) {
    return NextResponse.json({ error: 'Koneksi database Social TG belum dikonfigurasi.' }, { status: 503 });
  }
  if (!authorization.startsWith('Bearer ')) {
    return NextResponse.json({ error: 'Token TG tidak valid' }, { status: 401 });
  }
  try {
    const target = new URL(endpoint);
    target.searchParams.set('op', operation);
    req.nextUrl.searchParams.forEach((value, key) => {
      if (key !== 'op') target.searchParams.set(key, value);
    });
    const method = req.method.toUpperCase();
    const headers: Record<string, string> = {
      apikey: publishableKey,
      authorization,
      accept: 'application/json',
    };
    let body: string | undefined;
    if (method !== 'GET' && method !== 'HEAD') {
      headers['content-type'] = req.headers.get('content-type') || 'application/json';
      body = await req.text();
    }
    const response = await fetch(target, {
      method,
      headers,
      body,
      cache: 'no-store',
      signal: AbortSignal.timeout(15000),
    });
    const payload = await response.text();
    return new NextResponse(payload, {
      status: response.status,
      headers: {
        'content-type': response.headers.get('content-type') || 'application/json; charset=utf-8',
        'cache-control': 'no-store',
      },
    });
  } catch (error) {
    console.error('[tg-social-supabase-proxy]', error);
    return NextResponse.json({ error: 'Layanan Social TG belum tersedia.' }, { status: 503 });
  }
}

export async function proxySocialAdminRequest(req: NextRequest) {
  const endpoint = process.env.TG_SOCIAL_SUPABASE_FUNCTION_URL?.trim();
  const publishableKey = process.env.TG_SOCIAL_SUPABASE_PUBLISHABLE_KEY?.trim();
  const cookie = req.headers.get('cookie') || '';
  if (!endpoint || !publishableKey) {
    return NextResponse.json({ error: 'Koneksi database Social TG belum dikonfigurasi.' }, { status: 503 });
  }
  if (!cookie) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  try {
    const target = new URL(endpoint);
    target.searchParams.set('op', 'admin');
    const method = req.method.toUpperCase();
    const headers: Record<string, string> = { apikey: publishableKey, cookie, accept: 'application/json' };
    let body: string | undefined;
    if (method !== 'GET' && method !== 'HEAD') {
      headers['content-type'] = req.headers.get('content-type') || 'application/json';
      body = await req.text();
    }
    const response = await fetch(target, { method, headers, body, cache: 'no-store', signal: AbortSignal.timeout(15000) });
    const payload = await response.text();
    return new NextResponse(payload, {
      status: response.status,
      headers: { 'content-type': response.headers.get('content-type') || 'application/json; charset=utf-8', 'cache-control': 'no-store' },
    });
  } catch (error) {
    console.error('[tg-social-admin-proxy]', error);
    return NextResponse.json({ error: 'Layanan Social TG belum tersedia.' }, { status: 503 });
  }
}
