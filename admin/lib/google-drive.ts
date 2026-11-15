import { createHash } from 'node:crypto';

const DRIVE_API = 'https://www.googleapis.com/drive/v3';
const DRIVE_UPLOAD_API = 'https://www.googleapis.com/upload/drive/v3';
const ROOT_FOLDER_NAME = 'TG Launcher';
const COLLECTIONS = [
  'Users', 'Profiles', 'Settings', 'Licenses', 'License Keys', 'Plans',
  'Announcements', 'AI Usage', 'Audit Logs', 'Backups', 'Crash Logs', 'Uploads',
] as const;

type DriveFile = { id: string; name: string; mimeType: string; parents?: string[]; webViewLink?: string };
let cachedAccessToken = '';
let cachedAccessTokenExpiresAt = 0;

function requiredEnv(name: string) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(name + ' belum dikonfigurasi di Vercel.');
  return value;
}

async function accessToken() {
  if (cachedAccessToken && Date.now() < cachedAccessTokenExpiresAt - 60_000) return cachedAccessToken;
  const body = new URLSearchParams({
    client_id: requiredEnv('GOOGLE_DRIVE_CLIENT_ID'),
    client_secret: requiredEnv('GOOGLE_DRIVE_CLIENT_SECRET'),
    refresh_token: requiredEnv('GOOGLE_DRIVE_REFRESH_TOKEN'),
    grant_type: 'refresh_token',
  });
  const response = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
    cache: 'no-store',
  });
  const data = await response.json();
  if (!response.ok || !data.access_token) {
    throw new Error('Google Drive OAuth gagal: ' + (data.error_description || data.error || response.status));
  }
  cachedAccessToken = String(data.access_token);
  cachedAccessTokenExpiresAt = Date.now() + Number(data.expires_in || 3600) * 1000;
  return cachedAccessToken;
}

async function driveRequest<T>(path: string, init: RequestInit = {}, upload = false): Promise<T> {
  const token = await accessToken();
  const base = upload ? DRIVE_UPLOAD_API : DRIVE_API;
  const response = await fetch(base + path, {
    ...init,
    cache: 'no-store',
    headers: {
      Authorization: 'Bearer ' + token,
      ...(init.headers || {}),
    },
  });
  const raw = await response.text();
  let data: any = null;
  try { data = raw ? JSON.parse(raw) : null; } catch { data = raw; }
  if (!response.ok) {
    const reason = data?.error?.message || data?.error_description || 'HTTP ' + response.status;
    throw new Error('Google Drive API: ' + reason);
  }
  return data as T;
}

function escapeQuery(value: string) {
  return value.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}

async function findFolder(name: string, parentId?: string): Promise<DriveFile | null> {
  const clauses = [
    "mimeType='application/vnd.google-apps.folder'",
    "name='" + escapeQuery(name) + "'",
    'trashed=false',
  ];
  if (parentId) clauses.push("'" + escapeQuery(parentId) + "' in parents");
  const query = new URLSearchParams({
    q: clauses.join(' and '),
    pageSize: '10',
    fields: 'files(id,name,mimeType,parents,webViewLink)',
    spaces: 'drive',
  });
  const result = await driveRequest<{ files?: DriveFile[] }>('/files?' + query.toString());
  return result.files?.[0] || null;
}

async function createFolder(name: string, parentId?: string): Promise<DriveFile> {
  const metadata: Record<string, unknown> = {
    name,
    mimeType: 'application/vnd.google-apps.folder',
  };
  if (parentId) metadata.parents = [parentId];
  return driveRequest<DriveFile>('/files?fields=id,name,mimeType,parents,webViewLink', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(metadata),
  });
}

export async function ensureDriveFolder(name: string, parentId?: string) {
  return (await findFolder(name, parentId)) || createFolder(name, parentId);
}

export async function initializeDriveStorage() {
  const root = process.env.GOOGLE_DRIVE_ROOT_FOLDER_ID?.trim()
    ? await driveRequest<DriveFile>('/files/' + encodeURIComponent(requiredEnv('GOOGLE_DRIVE_ROOT_FOLDER_ID')) + '?fields=id,name,mimeType')
    : await ensureDriveFolder(ROOT_FOLDER_NAME);
  if (root.mimeType !== 'application/vnd.google-apps.folder') throw new Error('GOOGLE_DRIVE_ROOT_FOLDER_ID bukan folder Google Drive.');
  const folders: Record<string, string> = {};
  for (const name of COLLECTIONS) {
    const folder = await ensureDriveFolder(name, root.id);
    folders[name] = folder.id;
  }
  return { rootFolderId: root.id, rootFolderName: root.name, folders };
}

export async function getDriveStorageStatus() {
  const configured = Boolean(
    process.env.GOOGLE_DRIVE_CLIENT_ID &&
    process.env.GOOGLE_DRIVE_CLIENT_SECRET &&
    process.env.GOOGLE_DRIVE_REFRESH_TOKEN,
  );
  if (!configured) {
    return {
      configured: false,
      connected: false,
      rootFolderName: ROOT_FOLDER_NAME,
      requiredEnvironment: ['GOOGLE_DRIVE_CLIENT_ID', 'GOOGLE_DRIVE_CLIENT_SECRET', 'GOOGLE_DRIVE_REFRESH_TOKEN'],
    };
  }
  const token = await accessToken();
  const about = await driveRequest<{ user?: { displayName?: string; emailAddress?: string }; storageQuota?: { limit?: string; usage?: string } }>(
    '/about?fields=user(displayName,emailAddress),storageQuota(limit,usage)',
  );
  return {
    configured: true,
    connected: Boolean(token),
    account: about.user?.emailAddress || '',
    displayName: about.user?.displayName || '',
    storageQuota: about.storageQuota || {},
    rootFolderName: ROOT_FOLDER_NAME,
  };
}

function recordFileName(id: string) {
  const safe = createHash('sha256').update(id).digest('hex');
  return safe + '.json';
}

async function collectionFolder(collection: string) {
  if (!COLLECTIONS.includes(collection as (typeof COLLECTIONS)[number])) {
    throw new Error('Koleksi Drive tidak dikenal: ' + collection);
  }
  const initialized = await initializeDriveStorage();
  const id = initialized.folders[collection];
  if (!id) throw new Error('Folder koleksi tidak ditemukan.');
  return id;
}

export async function listDriveRecords<T>(collection: string): Promise<T[]> {
  const parentId = await collectionFolder(collection);
  const query = new URLSearchParams({
    q: "'" + parentId + "' in parents and trashed=false and mimeType='application/json'",
    pageSize: '1000',
    fields: 'nextPageToken,files(id,name,mimeType)',
    spaces: 'drive',
    orderBy: 'name',
  });
  const files: DriveFile[] = [];
  let pageToken = '';
  do {
    if (pageToken) query.set('pageToken', pageToken);
    const page = await driveRequest<{ files?: DriveFile[]; nextPageToken?: string }>('/files?' + query.toString());
    files.push(...(page.files || []));
    pageToken = page.nextPageToken || '';
  } while (pageToken);
  return Promise.all(files.map(async file => {
    const response = await driveRequest<{ id: string; name: string }>('/files/' + encodeURIComponent(file.id) + '?alt=media');
    return response as unknown as T;
  }));
}

export async function getDriveRecord<T>(collection: string, id: string): Promise<T | null> {
  const parentId = await collectionFolder(collection);
  const name = recordFileName(id);
  const query = new URLSearchParams({
    q: "'" + parentId + "' in parents and name='" + name + "' and trashed=false',
    pageSize: '2',
    fields: 'files(id,name)',
    spaces: 'drive',
  });
  const found = await driveRequest<{ files?: DriveFile[] }>('/files?' + query.toString());
  const file = found.files?.[0];
  if (!file) return null;
  const token = await accessToken();
  const response = await fetch(DRIVE_API + '/files/' + encodeURIComponent(file.id) + '?alt=media', {
    headers: { Authorization: 'Bearer ' + token },
    cache: 'no-store',
  });
  if (!response.ok) throw new Error('Gagal membaca data Drive (' + response.status + ').');
  return response.json() as Promise<T>;
}

export async function upsertDriveRecord(collection: string, id: string, value: unknown) {
  if (!id || id.length > 500) throw new Error('ID data Drive tidak valid.');
  const parentId = await collectionFolder(collection);
  const name = recordFileName(id);
  const query = new URLSearchParams({
    q: "'" + parentId + "' in parents and name='" + name + "' and trashed=false',
    pageSize: '2',
    fields: 'files(id,name)',
    spaces: 'drive',
  });
  const found = await driveRequest<{ files?: DriveFile[] }>('/files?' + query.toString());
  const existing = found.files?.[0];
  const payload = JSON.stringify(value);
  if (existing) {
    await driveRequest<DriveFile>('/files/' + encodeURIComponent(existing.id) + '?uploadType=media', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
    }, true);
    return { id, fileId: existing.id, updated: true };
  }
  const metadata = await driveRequest<DriveFile>('/files?fields=id,name,mimeType', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, mimeType: 'application/json', parents: [parentId] }),
  });
  await driveRequest<DriveFile>('/files/' + encodeURIComponent(metadata.id) + '?uploadType=media', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: payload,
  }, true);
  return { id, fileId: metadata.id, updated: false };
}
