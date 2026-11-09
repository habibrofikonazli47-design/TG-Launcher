import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'node:crypto';

function encryptionKey() {
  const master = process.env.ADMIN_SESSION_SECRET;
  if (!master) throw new Error('ADMIN_SESSION_SECRET belum dikonfigurasi.');
  return createHash('sha256').update(master).digest();
}

export function encryptLiveKitSecret(secret: string) {
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', encryptionKey(), iv);
  const encrypted = Buffer.concat([cipher.update(secret, 'utf8'), cipher.final()]);
  return ['v1', iv.toString('base64url'), cipher.getAuthTag().toString('base64url'), encrypted.toString('base64url')].join(':');
}

export function decryptLiveKitSecret(value: string) {
  const [version, ivText, tagText, dataText] = value.split(':');
  if (version !== 'v1' || !ivText || !tagText || !dataText) throw new Error('Format LiveKit secret tidak valid.');
  const decipher = createDecipheriv('aes-256-gcm', encryptionKey(), Buffer.from(ivText, 'base64url'));
  decipher.setAuthTag(Buffer.from(tagText, 'base64url'));
  return Buffer.concat([decipher.update(Buffer.from(dataText, 'base64url')), decipher.final()]).toString('utf8');
}
