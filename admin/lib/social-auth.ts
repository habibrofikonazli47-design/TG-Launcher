const FIREBASE_WEB_API_KEY = 'AIzaSyDIqvaTHjuwNboBXcVDoY1JYJntS0ECs0';
export type SocialIdentity = { uid: string; email: string; username: string };
export async function verifySocialIdentity(request: Request): Promise<SocialIdentity | null> {
  const header = request.headers.get('authorization') || '';
  const token = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
  if (!token) return null;
  const response = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${FIREBASE_WEB_API_KEY}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ idToken: token }), cache: 'no-store',
  });
  if (!response.ok) return null;
  const user = (await response.json())?.users?.[0];
  if (!user?.localId) return null;
  const email = String(user.email || '').toLowerCase();
  return { uid: String(user.localId), email, username: String(user.displayName || email.split('@')[0] || 'TG Player').slice(0, 32) };
}
