-- TG Launcher Private World invite metadata (Phase 1).
-- Review and apply only after a separate approved Supabase migration.
-- This file is intentionally NOT executed against production.

create table if not exists public.social_world_sessions (
  id uuid primary key,
  host_uid text not null,
  invite_code_hash text not null unique,
  world_name text not null default 'Dunia privat',
  minecraft_version text not null default '',
  status text not null default 'active'
    check (status in ('active', 'closed')),
  max_players integer not null default 2
    check (max_players between 2 and 8),
  expires_at timestamptz not null,
  created_at timestamptz not null default now(),
  closed_at timestamptz
);

create index if not exists social_world_sessions_host_active_idx
  on public.social_world_sessions (host_uid, status, expires_at desc);

create table if not exists public.social_world_members (
  session_id uuid not null references public.social_world_sessions(id) on delete cascade,
  user_id text not null,
  status text not null default 'invited'
    check (status in ('invited', 'accepted', 'joined', 'revoked', 'kicked')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (session_id, user_id)
);

create index if not exists social_world_members_user_status_idx
  on public.social_world_members (user_id, status, created_at desc);

alter table public.social_world_sessions enable row level security;
alter table public.social_world_members enable row level security;

-- No anon/authenticated policies or grants are added. The existing TG Social
-- Edge Function uses its server-side Supabase secret and performs per-user
-- Firebase identity, accepted-friendship, host ownership, invite-code and
-- invitee checks before each operation.
revoke all on table public.social_world_sessions from anon, authenticated;
revoke all on table public.social_world_members from anon, authenticated;
grant all on table public.social_world_sessions to service_role;
grant all on table public.social_world_members to service_role;
