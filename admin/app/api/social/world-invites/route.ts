import { NextRequest, NextResponse } from 'next/server';
import { isSocialFeatureEnabled } from '@/lib/social-db';
import { proxySocialRequest } from '@/lib/social-supabase';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

async function disabled() {
  return !(await isSocialFeatureEnabled('worldInvitesEnabled', false));
}

export async function GET(req: NextRequest) {
  if (await disabled()) return NextResponse.json({ error: 'Fitur undang teman ke dunia privat belum diaktifkan.' }, { status: 403, headers: { 'Cache-Control': 'no-store' } });
  return proxySocialRequest(req, 'world_invites');
}

export async function POST(req: NextRequest) {
  if (await disabled()) return NextResponse.json({ error: 'Fitur undang teman ke dunia privat belum diaktifkan.' }, { status: 403, headers: { 'Cache-Control': 'no-store' } });
  return proxySocialRequest(req, 'world_invites');
}

export async function PATCH(req: NextRequest) {
  if (await disabled()) return NextResponse.json({ error: 'Fitur undang teman ke dunia privat belum diaktifkan.' }, { status: 403, headers: { 'Cache-Control': 'no-store' } });
  return proxySocialRequest(req, 'world_invites');
}
