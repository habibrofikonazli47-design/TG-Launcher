import { createHmac, pbkdf2Sync, timingSafeEqual } from 'node:crypto';

const SESSION_COOKIE = 'tg_admin_session';
const SESSION_TTL = 60 * 60 * 8;
export function verifyAdminPassword(password: string) {
  const expected = process.env.ADMIN_PASSWORD_HASH || '';
  const [scheme, iterationsRaw, salt, digest] = expected.split('$');
  const iterations = Number(iterationsRaw);
  if (scheme !== 'pbkdf2' || !Number.isSafeInteger(iterations) || iterations < 100000 || !salt || !digest) return false;
  const actual = pbkdf2Sync(password, salt, iterations, 64, 'sha256').toString('hex');
  const a = Buffer.from(actual, 'hex'); const b = Buffer.from(digest, 'hex');
  return a.length === b.length && timingSafeEqual(a, b);
}
export function makeSession(email: string) {
  const secret = process.env.ADMIN_SESSION_SECRET;
  if (!secret) throw new Error('ADMIN_SESSION_SECRET belum dikonfigurasi');
  const payload = Buffer.from(JSON.stringify({ email, exp: Math.floor(Date.now()/1000)+SESSION_TTL })).toString('base64url');
  const sig = createHmac('sha256', secret).update(payload).digest('base64url');
  return payload+'.'+sig;
}
export function verifySession(value?: string) {
  if (!value || !process.env.ADMIN_SESSION_SECRET) return false;
  const [payload, signature] = value.split('.'); if (!payload || !signature) return false;
  const expected = createHmac('sha256', process.env.ADMIN_SESSION_SECRET).update(payload).digest();
  let supplied: Buffer; try { supplied = Buffer.from(signature, 'base64url'); } catch { return false; }
  if (expected.length !== supplied.length || !timingSafeEqual(expected, supplied)) return false;
  try { const data = JSON.parse(Buffer.from(payload, 'base64url').toString()); return data.exp > Math.floor(Date.now()/1000) && data.email === process.env.ADMIN_EMAIL; } catch { return false; }
}
export function sessionCookie(value: string) { return { name: SESSION_COOKIE, value, httpOnly: true, secure: process.env.NODE_ENV === 'production', sameSite: 'strict' as const, path: '/', maxAge: SESSION_TTL }; }
export function getCookieName() { return SESSION_COOKIE; }
export function supabaseConfig() {
  const url = process.env.SUPABASE_URL || '';
  const key = process.env.SUPABASE_SERVICE_ROLE_KEY || '';
  if (!url || !key) throw new Error('Koneksi database belum dikonfigurasi di Vercel.');
  return { url: url.replace(/\/$/, ''), key };
}
export async function db(path: string, init: RequestInit = {}) {
  const { url, key } = supabaseConfig();
  const response = await fetch(url + '/rest/v1/' + path, { ...init, cache: 'no-store', headers: { apikey: key, Authorization: 'Bearer '+key, 'Content-Type':'application/json', ...((init.headers || {}) as Record<string, string>) } });
  const text = await response.text(); let data: any = null; try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!response.ok) throw new Error(data?.message || data?.error || 'Database request gagal ('+response.status+')');
  return data;
}
export function requireSameOrigin(request: Request) {
  const origin = request.headers.get('origin');
  if (!origin) return;
  const host = request.headers.get('x-forwarded-host') || request.headers.get('host');
  if (host && new URL(origin).host !== host) throw new Error('Origin tidak diizinkan');
}
