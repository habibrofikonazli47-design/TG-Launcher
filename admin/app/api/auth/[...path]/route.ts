import { NextRequest, NextResponse } from 'next/server';
import { createHash, randomInt, timingSafeEqual } from 'node:crypto';
import { database, ensureSchema } from '@/lib/db';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';
const FIREBASE_WEB_API_KEY = 'AIzaSyDIqvaTHjuwNboBXcVDoY1jJYntS0ECsJ0';
const IDENTITY = 'https://identitytoolkit.googleapis.com/v1';
const SECURE_TOKEN = 'https://securetoken.googleapis.com/v1/token';

async function firebasePost(endpoint: string, body: Record<string, unknown>) {
  const response = await fetch(`${IDENTITY}/${endpoint}?key=${FIREBASE_WEB_API_KEY}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body), cache: 'no-store',
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const code = String(data?.error?.message || 'AUTH_ERROR');
    const messages: Record<string,string> = {
      EMAIL_EXISTS: 'Email sudah terdaftar.',
      INVALID_LOGIN_CREDENTIALS: 'Email atau password salah.',
      EMAIL_NOT_FOUND: 'Akun tidak ditemukan.',
      INVALID_PASSWORD: 'Password salah.',
      USER_DISABLED: 'Akun dinonaktifkan.',
      WEAK_PASSWORD: 'Password terlalu lemah.',
      TOO_MANY_ATTEMPTS_TRY_LATER: 'Terlalu banyak percobaan. Coba lagi nanti.',
    };
    throw new Error(messages[code] || code.replaceAll('_',' ').toLowerCase());
  }
  return data;
}

async function verifyToken(req: NextRequest) {
  const header = req.headers.get('authorization') || '';
  const token = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
  if (!token) return null;
  try {
    const data = await firebasePost('accounts:lookup', { idToken: token });
    const user = data?.users?.[0];
    return user?.localId && user?.email ? {
      uid: String(user.localId), email: String(user.email).toLowerCase(),
      username: String(user.displayName || user.email.split('@')[0]),
      photoURL: String(user.photoUrl || ''), token,
    } : null;
  } catch { return null; }
}

async function ensureProfile(uid: string, email: string, displayName: string) {
  await ensureSchema();
  const sql = database();
  const existing = await sql`SELECT id FROM profiles WHERE id=${uid} OR lower(email)=${email} LIMIT 1`;
  if (existing.length) {
    const previousUid = String(existing[0].id);
    if (previousUid !== uid) {
      // Keep all Premium ownership references aligned when a legacy profile row
      // is reconciled to the Firebase UID. Without this, a valid key can appear
      // to belong to a different account after login.
      await sql`UPDATE license_keys SET user_id=${uid} WHERE user_id=${previousUid}`;
      await sql`UPDATE licenses SET user_id=${uid} WHERE user_id=${previousUid}`;
      await sql`UPDATE license_devices SET user_id=${uid} WHERE user_id=${previousUid}`;
      await sql`UPDATE profiles SET id=${uid},email=${email},updated_at=now() WHERE id=${previousUid}`;
    }
  } else {
    const settings = await sql`SELECT value FROM app_settings WHERE key='general' LIMIT 1`;
    const defaultPlan = settings[0]?.value?.defaultPlan === 'premium' ? 'premium' : 'free';
    await sql`INSERT INTO profiles(id,email,display_name,plan,status) VALUES(${uid},${email},${displayName},${defaultPlan},'active') ON CONFLICT (id) DO NOTHING`;
  }
  const rows = await sql`SELECT id,email,display_name,plan,status,expires_at,created_at FROM profiles WHERE id=${uid} LIMIT 1`;
  if (!rows.length) throw new Error('Profil akun gagal dibuat.');
  return rows[0];
}

function activePlan(profile: any) {
  const expiry = profile?.expires_at ? new Date(profile.expires_at) : null;
  return profile?.plan === 'premium' && profile?.status === 'active' && (!expiry || expiry.getTime() > Date.now()) ? 'premium' : 'free';
}

async function userResponse(profile: any, extra: Record<string,unknown> = {}) {
  const plan = activePlan(profile);
  return {
    success: true, isActivated: plan === 'premium',
    user: {
      uid: profile.id, email: profile.email, username: profile.display_name || profile.email.split('@')[0],
      photoURL: '', tier: plan, expiresAt: profile.expires_at ? new Date(profile.expires_at).toISOString() : null,
      activatedAt: profile.created_at ? new Date(profile.created_at).getTime() : null,
    },
    ...extra,
  };
}

export async function POST(req: NextRequest, context: { params: Promise<{ path: string[] }> }) {
  const segments = (await context.params).path;
  const path = segments.join('/');
  try {
    const body = await req.json().catch(() => ({}));

    if (path === 'send-otp') {
      await ensureSchema();
      const email = String(body.email || '').trim().toLowerCase();
      const username = String(body.username || '').trim();
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return NextResponse.json({ error: 'Alamat email tidak valid.' }, { status: 400 });
      const sql = database();
      const rows = await sql`SELECT value FROM app_settings WHERE key='accounts' LIMIT 1`;
      const cfg = rows[0]?.value || {};
      if (cfg.otpEnabled !== true) return NextResponse.json({ error: 'Verifikasi OTP belum diaktifkan administrator.' }, { status: 403 });
      const apiKey = process.env.RESEND_API_KEY;
      const from = process.env.OTP_FROM_EMAIL;
      if (!apiKey || !from) return NextResponse.json({ error: 'Layanan email belum dikonfigurasi administrator.' }, { status: 503 });
      const existing = await sql`SELECT email,verified_at,sent_at FROM email_otp_verifications WHERE email=${email} LIMIT 1`;
      if (existing.length && existing[0].verified_at) return NextResponse.json({ success: true, message: 'Email sudah diverifikasi. Silakan lanjutkan pendaftaran.' });
      if (existing.length && Date.now() - new Date(existing[0].sent_at).getTime() < Math.max(30, Number(cfg.otpCooldownSeconds) || 60) * 1000) return NextResponse.json({ error: 'Tunggu sebelum meminta kode OTP berikutnya.' }, { status: 429 });
      const counts = await sql`SELECT count(*)::int AS total FROM email_otp_send_log WHERE email=${email} AND sent_at > now() - interval '1 hour'`;
      if (Number(counts[0]?.total || 0) >= Math.max(1, Number(cfg.otpMaxPerHour) || 5)) return NextResponse.json({ error: 'Batas pengiriman OTP per jam tercapai.' }, { status: 429 });
      const code = String(randomInt(0, 1000000)).padStart(6, '0');
      const expires = Math.max(1, Number(cfg.otpExpiryMinutes) || 5);
      const hash = createHash('sha256').update(email + ':' + code + ':' + (process.env.OTP_HASH_SECRET || apiKey)).digest('hex');
      await sql`INSERT INTO email_otp_verifications(email,username,code_hash,expires_at,sent_at,attempts,verified_at) VALUES(${email},${username},${hash},now() + (${expires} || ' minutes')::interval,now(),0,NULL) ON CONFLICT(email) DO UPDATE SET username=EXCLUDED.username,code_hash=EXCLUDED.code_hash,expires_at=EXCLUDED.expires_at,sent_at=now(),attempts=0,verified_at=NULL`;
      const sent = await fetch('https://api.resend.com/emails', { method:'POST', headers:{Authorization:'Bearer '+apiKey,'Content-Type':'application/json'}, body:JSON.stringify({from,to:[email],subject:'Kode verifikasi TG Launcher',html:`<div style="font-family:Arial,sans-serif"><h2>Verifikasi email TG Launcher</h2><p>Kode OTP kamu:</p><div style="font-size:32px;font-weight:bold;letter-spacing:8px">${code}</div><p>Kode berlaku ${expires} menit. Jangan bagikan kode ini kepada siapa pun.</p></div>`}), cache:'no-store' });
      if (!sent.ok) { await sql`DELETE FROM email_otp_verifications WHERE email=${email}`; return NextResponse.json({ error: 'Email OTP gagal dikirim. Periksa konfigurasi layanan email.' }, { status: 502 }); }
      await sql`INSERT INTO email_otp_send_log(email) VALUES(${email})`;
      return NextResponse.json({ success:true, message:'Kode OTP telah dikirim ke email.' });
    }

    if (path === 'verify-otp') {
      await ensureSchema();
      const email = String(body.email || '').trim().toLowerCase();
      const code = String(body.code || '').trim();
      if (!/^\d{6}$/.test(code)) return NextResponse.json({ error:'Masukkan kode OTP 6 digit.' }, { status:400 });
      const sql = database();
      const rows = await sql`SELECT code_hash,expires_at,attempts,verified_at FROM email_otp_verifications WHERE email=${email} LIMIT 1`;
      if (!rows.length || rows[0].verified_at || new Date(rows[0].expires_at).getTime() < Date.now()) return NextResponse.json({ error:'Kode OTP tidak tersedia atau sudah kedaluwarsa.' }, { status:400 });
      const cfgRows = await sql`SELECT value FROM app_settings WHERE key='accounts' LIMIT 1`;
      const maxAttempts = Math.max(1, Number(cfgRows[0]?.value?.otpMaxAttempts) || 5);
      if (Number(rows[0].attempts) >= maxAttempts) { await sql`DELETE FROM email_otp_verifications WHERE email=${email}`; return NextResponse.json({ error:'Batas percobaan OTP tercapai. Minta kode baru.' }, { status:429 }); }
      const actual = createHash('sha256').update(email + ':' + code + ':' + (process.env.OTP_HASH_SECRET || process.env.RESEND_API_KEY || 'unset')).digest('hex');
      const expected = String(rows[0].code_hash);
      const valid = actual.length === expected.length && timingSafeEqual(Buffer.from(actual),Buffer.from(expected));
      if (!valid) { await sql`UPDATE email_otp_verifications SET attempts=attempts+1 WHERE email=${email}`; return NextResponse.json({ error:'Kode OTP salah.' }, { status:400 }); }
      await sql`UPDATE email_otp_verifications SET verified_at=now() WHERE email=${email}`;
      return NextResponse.json({ success:true, message:'Email berhasil diverifikasi. Silakan buat akun.' });
    }

    if (path === 'android/login') {
      const auth = await firebasePost('accounts:signInWithPassword', {
        email: String(body.email || '').trim().toLowerCase(), password: String(body.password || ''), returnSecureToken: true,
      });
      const profile = await ensureProfile(String(auth.localId), String(auth.email).toLowerCase(), String(auth.displayName || auth.email.split('@')[0]));
      if (profile.status === 'blocked') return NextResponse.json({ error: 'Akun ini diblokir oleh administrator.' }, { status: 403 });
      return NextResponse.json(await userResponse(profile, { idToken: auth.idToken, refreshToken: auth.refreshToken, message: 'Login berhasil.' }));
    }

    if (path === 'android/register') {
      await ensureSchema();
      const policyRows = await database()`SELECT key,value FROM app_settings WHERE key IN ('general','accounts')`;
      const policies = Object.fromEntries(policyRows.map((row:any) => [row.key, row.value || {}]));
      if (policies.general?.allowRegistration === false || policies.accounts?.registrationEnabled === false) {
        return NextResponse.json({ error: 'Pendaftaran akun sedang dinonaktifkan oleh administrator.' }, { status: 403 });
      }
      const accountSettings = policies.accounts || {};
      if (accountSettings.otpEnabled === true && accountSettings.otpRequireEmailVerification !== false) {
        const verified = await database()`SELECT email FROM email_otp_verifications WHERE email=${String(body.email || '').trim().toLowerCase()} AND verified_at > now() - interval '15 minutes' LIMIT 1`;
        if (!verified.length) return NextResponse.json({ error:'Verifikasi email OTP wajib diselesaikan sebelum membuat akun.' }, { status:403 });
      }
      const auth = await firebasePost('accounts:signUp', {
        email: String(body.email || '').trim().toLowerCase(), password: String(body.password || ''), returnSecureToken: true,
      });
      const displayName = String(body.username || auth.email.split('@')[0]).trim();
      await firebasePost('accounts:update', { idToken: auth.idToken, displayName, returnSecureToken: true });
      const profile = await ensureProfile(String(auth.localId), String(auth.email).toLowerCase(), displayName);
      await database()`DELETE FROM email_otp_verifications WHERE email=${String(auth.email).toLowerCase()}`;
      return NextResponse.json(await userResponse(profile, { idToken: auth.idToken, refreshToken: auth.refreshToken, message: 'Registrasi berhasil.' }));
    }

    if (path === 'reset-password') {
      await firebasePost('accounts:sendOobCode', { requestType: 'PASSWORD_RESET', email: String(body.email || '').trim().toLowerCase() });
      return NextResponse.json({ success: true, message: 'Link reset password telah dikirim ke email.' });
    }

    if (path === 'android/session-token') {
      const refreshToken = String(body.refreshToken || '');
      if (!refreshToken) return NextResponse.json({ error: 'Refresh token diperlukan' }, { status: 400 });
      const response = await fetch(`${SECURE_TOKEN}?key=${FIREBASE_WEB_API_KEY}`, {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ grant_type: 'refresh_token', refresh_token: refreshToken }), cache: 'no-store',
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) return NextResponse.json({ error: 'Sesi kedaluwarsa. Silakan login kembali.' }, { status: 401 });
      return NextResponse.json({ success: true, idToken: data.id_token, refreshToken: data.refresh_token });
    }

    if (path === 'android/activate') {
      const identity = await verifyToken(req);
      if (!identity) return NextResponse.json({ error: 'Sesi tidak valid' }, { status: 401 });
      if (String(body.uid || '') !== identity.uid) return NextResponse.json({ error: 'UID tidak cocok dengan sesi' }, { status: 403 });
      await ensureSchema();
      const sql = database();
      const keys = await sql`UPDATE license_keys SET used_count=used_count+1,status=CASE WHEN used_count+1>=max_uses THEN 'used' ELSE 'active' END WHERE license_key=${String(body.licenseKey || '').trim().toUpperCase()} AND status IN ('unused','active') AND used_count<max_uses AND (expires_at IS NULL OR expires_at>now()) RETURNING plan,expires_at`;
      if (!keys.length) return NextResponse.json({ success: false, isActivated: false, error: 'License key tidak valid, kedaluwarsa, atau sudah digunakan.' }, { status: 400 });
      const key = keys[0];
      const expiry = key.expires_at ? new Date(key.expires_at) : null;
      await sql`UPDATE profiles SET plan=${key.plan},status='active',expires_at=${expiry},updated_at=now() WHERE id=${identity.uid}`;
      return NextResponse.json({ success: true, isActivated: key.plan === 'premium', tier: key.plan, expiresAt: expiry ? expiry.toISOString() : 'lifetime', message: 'Paket berhasil diaktifkan.' });
    }

    if (path === 'android/update-profile') {
      const identity = await verifyToken(req);
      if (!identity) return NextResponse.json({ error: 'Sesi tidak valid' }, { status: 401 });
      if (String(body.uid || '') !== identity.uid) return NextResponse.json({ error: 'UID tidak cocok dengan sesi' }, { status: 403 });
      const displayName = String(body.newUsername || identity.username).trim();
      const photoURL = String(body.photoURL || identity.photoURL);
      await firebasePost('accounts:update', { idToken: identity.token, displayName, photoUrl: photoURL, returnSecureToken: true });
      await ensureSchema();
      const sql = database();
      await sql`UPDATE profiles SET display_name=${displayName},updated_at=now() WHERE id=${identity.uid}`;
      const profile = await ensureProfile(identity.uid, identity.email, displayName);
      return NextResponse.json({ success: true, user: { uid: identity.uid, username: displayName, photoURL } });
    }

    return NextResponse.json({ error: 'Endpoint tidak ditemukan' }, { status: 404 });
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Permintaan gagal';
    return NextResponse.json({ error: message }, { status: 400 });
  }
}
