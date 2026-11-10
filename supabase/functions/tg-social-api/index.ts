import { createClient } from "npm:@supabase/supabase-js@2";

const FIREBASE_WEB_API_KEY = "AIzaSyDIqvaTHjuwNboBXcVDoY1JYJntS0ECs0";
const cors = {
  "Access-Control-Allow-Origin": "https://tg-launcher-admin-sigma.vercel.app",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, PATCH, OPTIONS",
  "Content-Type": "application/json",
  "Cache-Control": "no-store",
};

function reply(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: cors });
}
function fail(message: string, status = 400) { return reply({ error: message }, status); }

async function identify(req: Request) {
  const auth = req.headers.get("authorization") || "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (!token || token.length > 8192) return null;
  try {
    const response = await fetch(
      `https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${FIREBASE_WEB_API_KEY}`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ idToken: token }) },
    );
    if (!response.ok) return null;
    const user = (await response.json())?.users?.[0];
    if (!user?.localId) return null;
    const email = String(user.email || "").toLowerCase();
    return { uid: String(user.localId), email, username: String(user.displayName || email.split("@")[0] || "TG Player").slice(0, 32) };
  } catch { return null; }
}

function database() {
  const url = Deno.env.get("SUPABASE_URL");
  const secretKeys = Deno.env.get("SUPABASE_SECRET_KEYS");
  const legacySecret = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const secret = secretKeys ? JSON.parse(secretKeys)?.default : legacySecret;
  if (!url || !secret) throw new Error("Supabase server credentials are not available in the Edge Function runtime.");
  return createClient(url, secret, { auth: { persistSession: false, autoRefreshToken: false } });
}


const ADMIN_PUBLIC_KEY_SPKI_B64 = "MCowBQYDK2VwAyEABGY52bwoJjThdA3wj7i3wmuKeJuKXAvZbRaTzYLXUmw=";

function decodeB64Url(value: string) {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  return Uint8Array.from(atob(padded), (char) => char.charCodeAt(0));
}

async function authorizeAdmin(req: Request) {
  const token = req.headers.get("x-tg-admin-assertion") || "";
  const parts = token.split(".");
  if (parts.length !== 3 || token.length > 4096) return false;
  try {
    const header = JSON.parse(new TextDecoder().decode(decodeB64Url(parts[0])));
    const payload = JSON.parse(new TextDecoder().decode(decodeB64Url(parts[1])));
    const now = Math.floor(Date.now() / 1000);
    if (header.alg !== "EdDSA" || header.typ !== "JWT" ||
        payload.sub !== "tg-admin" || payload.aud !== "tg-social-admin" ||
        !payload.jti || !Number.isFinite(payload.iat) || !Number.isFinite(payload.exp) ||
        payload.iat > now + 10 || payload.exp <= now || payload.exp - payload.iat > 60) return false;
    const publicKeyBytes = Uint8Array.from(atob(ADMIN_PUBLIC_KEY_SPKI_B64), (char) => char.charCodeAt(0));
    const publicKey = await crypto.subtle.importKey("spki", publicKeyBytes, { name: "Ed25519" }, false, ["verify"]);
    return await crypto.subtle.verify(
      { name: "Ed25519" },
      publicKey,
      decodeB64Url(parts[2]),
      new TextEncoder().encode(parts[0] + "." + parts[1]),
    );
  } catch { return false; }
}

async function handle(req: Request) {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  const url = new URL(req.url);
  const op = url.searchParams.get("op") || "";

  if (op === "health" && req.method === "GET") {
    const healthDb = database();
    const { error } = await healthDb.from("social_profiles").select("user_id", { head: true, count: "exact" });
    if (error) return fail("Database Social belum siap", 503);
    return reply({ success: true, service: "TG Social", database: "connected" });
  }

  if (op === "admin") {
    if (!(await authorizeAdmin(req))) return fail("Unauthorized", 401);
    const adminDb = database();
    const adminBody = ["POST", "PATCH"].includes(req.method) ? await req.json().catch(() => ({})) : {};
    if (req.method === "GET") {
      const [profileCount, friendshipCount, messageCount, roomCount, reportCount, profiles, rooms, reports] = await Promise.all([
        adminDb.from("social_profiles").select("user_id", { count: "exact", head: true }),
        adminDb.from("social_friendships").select("id", { count: "exact", head: true }).eq("status", "accepted"),
        adminDb.from("social_messages").select("id", { count: "exact", head: true }).is("deleted_at", null),
        adminDb.from("social_voice_rooms").select("id", { count: "exact", head: true }).eq("is_active", true),
        adminDb.from("social_reports").select("id", { count: "exact", head: true }).in("status", ["open", "reviewing"]),
        adminDb.from("social_profiles").select("user_id,username,avatar_url,platform,presence,last_seen_at,created_at").order("updated_at", { ascending: false }).limit(100),
        adminDb.from("social_voice_rooms").select("id,room_name,created_by,visibility,max_participants,is_active,created_at").order("created_at", { ascending: false }).limit(50),
        adminDb.from("social_reports").select("id,reporter_id,target_user_id,reason,details,status,created_at").order("created_at", { ascending: false }).limit(100),
      ]);
      const error = [profileCount, friendshipCount, messageCount, roomCount, reportCount, profiles, rooms, reports].find((x: any) => x.error)?.error;
      if (error) return fail("Gagal memuat data Social TG", 503);
      return reply({
        success: true,
        stats: {
          profiles: profileCount.count || 0, friendships: friendshipCount.count || 0,
          messages: messageCount.count || 0, active_voice_rooms: roomCount.count || 0,
          open_reports: reportCount.count || 0,
        },
        profiles: profiles.data || [], rooms: rooms.data || [], reports: reports.data || [],
      });
    }
    if (req.method === "PATCH") {
      const action = String(adminBody.action || "");
      if (action === "report_status") {
        const id = Number(adminBody.id), status = String(adminBody.status || "");
        if (!Number.isSafeInteger(id) || id <= 0 || !["open", "reviewing", "resolved", "dismissed"].includes(status)) return fail("Data laporan tidak valid");
        const { data, error } = await adminDb.from("social_reports").update({ status }).eq("id", id).select("id,status").maybeSingle();
        if (error) return fail("Gagal memperbarui laporan", 503);
        return data ? reply({ success: true, report: data }) : fail("Laporan tidak ditemukan", 404);
      }
      if (action === "close_room") {
        const id = String(adminBody.id || "");
        if (!id) return fail("ID room diperlukan");
        const { data, error } = await adminDb.from("social_voice_rooms").update({ is_active: false, closed_at: new Date().toISOString() }).eq("id", id).select("id,is_active").maybeSingle();
        if (error) return fail("Gagal menutup voice room", 503);
        return data ? reply({ success: true, room: data }) : fail("Voice room tidak ditemukan", 404);
      }
      if (action === "remove_friendship") {
        const id = Number(adminBody.id);
        if (!Number.isSafeInteger(id) || id <= 0) return fail("ID pertemanan tidak valid");
        const { data, error } = await adminDb.from("social_friendships").delete().eq("id", id).select("id").maybeSingle();
        if (error) return fail("Gagal menghapus pertemanan", 503);
        return data ? reply({ success: true }) : fail("Relasi tidak ditemukan", 404);
      }
      if (action === "set_presence") {
        const uid = String(adminBody.userId || ""), presence = String(adminBody.presence || "offline");
        if (!uid || !["online", "offline", "in_game", "away"].includes(presence)) return fail("Status pengguna tidak valid");
        const { data, error } = await adminDb.from("social_profiles").update({ presence, last_seen_at: new Date().toISOString(), updated_at: new Date().toISOString() }).eq("user_id", uid).select("user_id,presence").maybeSingle();
        if (error) return fail("Gagal mengubah status pengguna", 503);
        return data ? reply({ success: true, profile: data }) : fail("Profil tidak ditemukan", 404);
      }
      return fail("Aksi moderasi tidak didukung");
    }
    return fail("Metode admin tidak didukung", 405);
  }

  const user = await identify(req);
  if (!user) return fail("Token TG tidak valid", 401);
  const db = database();
  const body = ["POST", "PATCH"].includes(req.method) ? await req.json().catch(() => ({})) : {};

  if (op === "profile" && req.method === "GET") {
    const { error: upsertError } = await db.from("social_profiles").upsert({
      user_id: user.uid, username: user.username, username_lower: user.username.toLowerCase(),
    }, { onConflict: "user_id", ignoreDuplicates: true });
    if (upsertError) return fail("Gagal menyiapkan profil sosial", 503);
    const { data, error } = await db.from("social_profiles")
      .select("user_id,username,avatar_url,bio,platform,presence,last_seen_at")
      .eq("user_id", user.uid).maybeSingle();
    if (error) return fail("Gagal memuat profil sosial", 503);
    return reply({ success: true, profile: data });
  }

  if (op === "profile" && ["PATCH", "POST"].includes(req.method)) {
    const username = String(body.username || "").trim().slice(0, 32);
    const bio = String(body.bio || "").trim().slice(0, 160);
    const avatar = String(body.avatarUrl || "").trim().slice(0, 2048);
    if (username.length < 3 || !/^[a-zA-Z0-9_ .-]+$/.test(username)) return fail("Username 3-32 karakter; gunakan huruf, angka, spasi, titik, atau garis bawah.", 400);
    const { data, error } = await db.from("social_profiles").update({
      username, username_lower: username.toLowerCase(), bio, avatar_url: avatar, updated_at: new Date().toISOString(),
    }).eq("user_id", user.uid).select("user_id,username,avatar_url,bio,platform,presence,last_seen_at").maybeSingle();
    if (error?.code === "23505") return fail("Username sudah digunakan.", 409);
    if (error) return fail("Gagal memperbarui profil", 503);
    if (!data) return fail("Profil belum dibuat; panggil GET terlebih dahulu.", 404);
    return reply({ success: true, profile: data });
  }

  if (op === "friends" && req.method === "GET") {
    const { data: links, error } = await db.from("social_friendships")
      .select("id,requester_id,addressee_id,status,created_at,updated_at")
      .or(`requester_id.eq.${user.uid},addressee_id.eq.${user.uid}`)
      .neq("status", "blocked").order("updated_at", { ascending: false }).limit(500);
    if (error) return fail("Gagal memuat daftar teman", 503);
    const ids = [...new Set((links || []).map((x: any) => x.requester_id === user.uid ? x.addressee_id : x.requester_id))];
    const { data: profiles, error: profileError } = ids.length
      ? await db.from("social_profiles").select("user_id,username,avatar_url,presence,last_seen_at").in("user_id", ids)
      : { data: [], error: null };
    if (profileError) return fail("Gagal memuat profil teman", 503);
    const byId = new Map((profiles || []).map((p: any) => [p.user_id, p]));
    const items = (links || []).map((f: any) => ({
      ...f, ...(byId.get(f.requester_id === user.uid ? f.addressee_id : f.requester_id) || {}),
    })).sort((a: any, b: any) => {
      const aRank = a.status === "pending" && a.addressee_id === user.uid ? 0 : a.status === "accepted" ? 1 : 2;
      const bRank = b.status === "pending" && b.addressee_id === user.uid ? 0 : b.status === "accepted" ? 1 : 2;
      return aRank - bRank;
    });
    return reply({ success: true, items });
  }

  if (op === "friends" && req.method === "POST") {
    const action = String(body.action || "request");
    if (action === "request") {
      const targetUid = String(body.targetUserId || "").trim();
      const targetName = String(body.username || "").trim().toLowerCase();
      if (!targetUid && (!targetName || targetName.length > 32)) return fail("Pengguna tujuan tidak valid");
      let targetQuery = db.from("social_profiles").select("user_id").limit(1);
      targetQuery = targetUid ? targetQuery.eq("user_id", targetUid) : targetQuery.eq("username_lower", targetName);
      const { data: found, error } = await targetQuery;
      if (error) return fail("Gagal mencari pengguna", 503);
      if (!found?.length) return fail("Pengguna TG tidak ditemukan atau belum memiliki profil sosial", 404);
      const target = String(found[0].user_id);
      if (target === user.uid) return fail("Tidak dapat menambahkan akun sendiri");
      const { data: existing } = await db.from("social_friendships").select("id,status")
        .eq("requester_id", user.uid).eq("addressee_id", target).maybeSingle();
      if (existing?.status === "blocked") return fail("Pengguna ini telah diblokir.", 403);
      const { data: reverse } = await db.from("social_friendships").select("id,status")
        .eq("requester_id", target).eq("addressee_id", user.uid).maybeSingle();
      if (reverse?.status === "blocked") return fail("Permintaan teman tidak dapat dikirim ke pengguna ini.", 403);
      if (reverse?.status === "pending") {
        const { data, error: updateError } = await db.from("social_friendships").update({
          status: "accepted", updated_at: new Date().toISOString(),
        }).eq("id", reverse.id).select("id,status").single();
        if (updateError) return fail("Gagal menerima permintaan teman", 503);
        return reply({ success: true, friendship: data, autoAccepted: true });
      }
      const { data, error: insertError } = await db.from("social_friendships").upsert({
        requester_id: user.uid, addressee_id: target, status: "pending", updated_at: new Date().toISOString(),
      }, { onConflict: "requester_id,addressee_id" }).select("id,status").single();
      if (insertError) return fail("Gagal mengirim permintaan teman", 503);
      return reply({ success: true, friendship: data });
    }
    const id = Number(body.id);
    if (!Number.isSafeInteger(id) || id <= 0) return fail("Permintaan teman tidak valid");
    if (action === "accept") {
      const { data, error } = await db.from("social_friendships").update({ status: "accepted", updated_at: new Date().toISOString() })
        .eq("id", id).eq("addressee_id", user.uid).eq("status", "pending").select("id,status").maybeSingle();
      if (error) return fail("Gagal menerima permintaan", 503);
      return data ? reply({ success: true, friendship: data }) : fail("Permintaan tidak ditemukan", 404);
    }
    if (action === "block") {
      const { data, error } = await db.from("social_friendships").update({ status: "blocked", updated_at: new Date().toISOString() })
        .eq("id", id).or(`requester_id.eq.${user.uid},addressee_id.eq.${user.uid}`).select("id,status").maybeSingle();
      if (error) return fail("Gagal memblokir pengguna", 503);
      return data ? reply({ success: true, friendship: data }) : fail("Relasi tidak ditemukan", 404);
    }
    if (["decline", "remove", "unblock"].includes(action)) {
      const { data, error } = await db.from("social_friendships").delete()
        .eq("id", id).or(`requester_id.eq.${user.uid},addressee_id.eq.${user.uid}`).select("id").maybeSingle();
      if (error) return fail("Gagal menghapus pertemanan", 503);
      return data ? reply({ success: true }) : fail("Pertemanan tidak ditemukan", 404);
    }
    return fail("Aksi tidak didukung");
  }

  if (op === "search" && req.method === "GET") {
    const q = String(url.searchParams.get("q") || "").trim().toLowerCase().slice(0, 32);
    const online = url.searchParams.get("online") === "true";
    const limit = Math.max(1, Math.min(50, Number(url.searchParams.get("limit") || 20) || 20));
    let query = db.from("social_profiles").select("user_id,username,avatar_url,bio,platform,presence,last_seen_at").neq("user_id", user.uid).limit(limit);
    if (online) query = query.in("presence", ["online", "in_game"]).gt("last_seen_at", new Date(Date.now() - 90000).toISOString()).order("last_seen_at", { ascending: false });
    else query = query.ilike("username_lower", `%${q.replace(/[\\%_]/g, "\\$&")}%`).order("username_lower", { ascending: true });
    const { data, error } = await query;
    if (error) return fail("Gagal mencari pengguna TG", 503);
    return reply({ success: true, items: data || [] });
  }

  if (op === "presence" && req.method === "POST") {
    const status = String(body.status || "online");
    if (!["online", "offline", "in_game", "away"].includes(status)) return fail("Status tidak valid");
    const { error } = await db.from("social_profiles").upsert({
      user_id: user.uid, username: user.username, username_lower: user.username.toLowerCase(),
      presence: status, last_seen_at: new Date().toISOString(), updated_at: new Date().toISOString(),
    }, { onConflict: "user_id" });
    if (error) return fail("Gagal memperbarui status online", 503);
    return reply({ success: true, status });
  }

  if (op === "messages" && req.method === "GET") {
    const conversationId = String(url.searchParams.get("conversationId") || "");
    if (!conversationId) return fail("conversationId diperlukan");
    let { data: member, error: memberError } = await db.from("social_conversation_members").select("user_id")
      .eq("conversation_id", conversationId).eq("user_id", user.uid).maybeSingle();
    if (memberError) return fail("Gagal memeriksa akses percakapan", 503);
    if (!member) {
      const target = conversationId.startsWith("dm_") ? conversationId.slice(3).split("_").find((id) => id !== user.uid) || "" : "";
      if (!target) return fail("Akses percakapan ditolak", 403);
      const { data: f1 } = await db.from("social_friendships").select("id").eq("status", "accepted").eq("requester_id", user.uid).eq("addressee_id", target).maybeSingle();
      const { data: f2 } = await db.from("social_friendships").select("id").eq("status", "accepted").eq("requester_id", target).eq("addressee_id", user.uid).maybeSingle();
      if (!f1 && !f2) return fail("Percakapan hanya tersedia untuk teman yang diterima", 403);
      await db.from("social_conversations").upsert({ id: conversationId, conversation_type: "direct", created_by: user.uid }, { onConflict: "id", ignoreDuplicates: true });
      await db.from("social_conversation_members").upsert([
        { conversation_id: conversationId, user_id: user.uid }, { conversation_id: conversationId, user_id: target },
      ], { onConflict: "conversation_id,user_id", ignoreDuplicates: true });
      member = { user_id: user.uid };
    }
    const { data, error } = await db.from("social_messages")
      .select("id,sender_id,body,attachment_url,created_at,edited_at")
      .eq("conversation_id", conversationId).is("deleted_at", null).order("created_at", { ascending: false }).limit(100);
    if (error) return fail("Gagal memuat pesan", 503);
    return reply({ success: true, messages: (data || []).reverse() });
  }

  if (op === "messages" && req.method === "POST") {
    const target = String(body.targetUserId || "");
    const message = String(body.message || "").trim();
    const attachmentUrl = String(body.attachmentUrl || "").trim().slice(0, 2048);
    if (!target || target === user.uid || (!message && !attachmentUrl) || message.length > 4000) return fail("Penerima atau isi pesan tidak valid");
    const { data: f1 } = await db.from("social_friendships").select("id").eq("status", "accepted").eq("requester_id", user.uid).eq("addressee_id", target).maybeSingle();
    const { data: f2 } = await db.from("social_friendships").select("id").eq("status", "accepted").eq("requester_id", target).eq("addressee_id", user.uid).maybeSingle();
    if (!f1 && !f2) return fail("Pesan hanya dapat dikirim kepada teman yang sudah menerima permintaan", 403);
    const [a, b] = [user.uid, target].sort();
    const conversationId = "dm_" + a + "_" + b;
    const { error: convError } = await db.from("social_conversations").upsert({ id: conversationId, conversation_type: "direct", created_by: user.uid }, { onConflict: "id", ignoreDuplicates: true });
    if (convError) return fail("Gagal menyiapkan percakapan", 503);
    const { error: memberError } = await db.from("social_conversation_members").upsert([
      { conversation_id: conversationId, user_id: user.uid }, { conversation_id: conversationId, user_id: target },
    ], { onConflict: "conversation_id,user_id", ignoreDuplicates: true });
    if (memberError) return fail("Gagal menyiapkan anggota percakapan", 503);
    const { data, error } = await db.from("social_messages").insert({
      id: crypto.randomUUID(), conversation_id: conversationId, sender_id: user.uid, body: message, attachment_url: attachmentUrl,
    }).select("id,conversation_id,sender_id,body,attachment_url,created_at").single();
    if (error) return fail("Gagal mengirim pesan", 503);
    return reply({ success: true, message: data }, 201);
  }

  if (op === "voice_rooms" && req.method === "GET") {
    const { data: rooms, error } = await db.from("social_voice_rooms")
      .select("id,room_name,created_by,visibility,max_participants,created_at")
      .eq("is_active", true).order("created_at", { ascending: false }).limit(100);
    if (error) return fail("Gagal memuat voice room TG", 503);
    const { data: friendLinks, error: friendError } = await db.from("social_friendships")
      .select("requester_id,addressee_id").eq("status", "accepted")
      .or(`requester_id.eq.${user.uid},addressee_id.eq.${user.uid}`);
    if (friendError) return fail("Gagal memeriksa akses voice room", 503);
    const friendIds = new Set((friendLinks || []).map((f: any) => f.requester_id === user.uid ? f.addressee_id : f.requester_id));
    const visibleRooms = (rooms || []).filter((room: any) =>
      room.created_by === user.uid ||
      room.visibility === "public" ||
      (room.visibility === "friends" && friendIds.has(room.created_by))
    );
    const ids = [...new Set(visibleRooms.map((r: any) => r.created_by))];
    const { data: hosts } = ids.length ? await db.from("social_profiles").select("user_id,username").in("user_id", ids) : { data: [] };
    const byId = new Map((hosts || []).map((p: any) => [p.user_id, p.username]));
    return reply({ success: true, rooms: visibleRooms.map((r: any) => ({ ...r, host_name: byId.get(r.created_by) || null })) });
  }

  if (op === "voice_rooms" && req.method === "POST") {
    const action = String(body.action || "join");
    let roomName = String(body.roomName || "").trim().slice(0, 80);
    if (action === "create") {
      if (!roomName) return fail("Nama voice room diperlukan");
      const id = "tg_" + crypto.randomUUID().replace(/-/g, "").slice(0, 20);
      const livekitRoom = "tg-social-" + id;
      const visibility = ["public", "friends", "private"].includes(String(body.visibility)) ? String(body.visibility) : "friends";
      const max = Math.max(2, Math.min(50, Number(body.maxParticipants) || 16));
      const { error } = await db.from("social_voice_rooms").insert({
        id, room_name: roomName, created_by: user.uid, livekit_room_name: livekitRoom,
        visibility, max_participants: max,
      });
      if (error) return fail("Gagal membuat voice room", 503);
      return reply({ success: true, id, roomName: livekitRoom, room: { id, room_name: roomName, visibility, max_participants: max } }, 201);
    }
    if (action === "join") {
      if (!roomName || !/^[A-Za-z0-9_-]{1,80}$/.test(roomName)) return fail("ID voice room tidak valid");
      const { data: room, error } = await db.from("social_voice_rooms")
        .select("id,created_by,visibility,livekit_room_name")
        .or(`id.eq.${roomName},livekit_room_name.eq.${roomName}`)
        .eq("is_active", true).maybeSingle();
      if (error) return fail("Gagal memeriksa voice room", 503);
      if (!room) return fail("Voice room tidak ditemukan atau sudah ditutup", 404);
      if (room.created_by !== user.uid && room.visibility === "private") return fail("Voice room ini bersifat pribadi", 403);
      if (room.created_by !== user.uid && room.visibility === "friends") {
        const { data: friendA } = await db.from("social_friendships").select("id")
          .eq("status", "accepted").eq("requester_id", user.uid).eq("addressee_id", room.created_by).maybeSingle();
        const { data: friendB } = await db.from("social_friendships").select("id")
          .eq("status", "accepted").eq("requester_id", room.created_by).eq("addressee_id", user.uid).maybeSingle();
        if (!friendA && !friendB) return fail("Voice room hanya untuk teman yang diterima", 403);
      }
      return reply({ success: true, roomName: room.livekit_room_name, id: room.id });
    }
    return fail("Aksi voice tidak didukung");
  }

  if (op === "reports" && req.method === "POST") {
    const target = String(body.targetUserId || "");
    const reason = String(body.reason || "").trim().slice(0, 80);
    const details = String(body.details || "").trim().slice(0, 1000);
    if (!target || target === user.uid || !reason) return fail("Laporan tidak valid");
    const { data, error } = await db.from("social_reports").insert({
      reporter_id: user.uid, target_user_id: target, reason, details,
    }).select("id,status,created_at").single();
    if (error) return fail("Gagal mengirim laporan", 503);
    return reply({ success: true, report: data }, 201);
  }


  if (op === "world_invites" && ["GET", "POST", "PATCH"].includes(req.method)) {
    const now = new Date();
    const nowIso = now.toISOString();
    const isFriend = async (otherUid: string) => {
      const { data: forward, error: e1 } = await db.from("social_friendships").select("id")
        .eq("status", "accepted").eq("requester_id", user.uid).eq("addressee_id", otherUid).maybeSingle();
      const { data: reverse, error: e2 } = await db.from("social_friendships").select("id")
        .eq("status", "accepted").eq("requester_id", otherUid).eq("addressee_id", user.uid).maybeSingle();
      if (e1 || e2) throw new Error("Gagal memeriksa pertemanan");
      return Boolean(forward || reverse);
    };
    const hashInviteCode = async (value: string) => {
      const bytes = new TextEncoder().encode(value.toUpperCase());
      const digest = await crypto.subtle.digest("SHA-256", bytes);
      return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
    };
    const makeInviteCode = () => {
      const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
      const bytes = crypto.getRandomValues(new Uint8Array(8));
      return Array.from(bytes, (b) => alphabet[b % alphabet.length]).join("");
    };

    if (req.method === "GET") {
      const { data: hosted, error: hostError } = await db.from("social_world_sessions")
        .select("id,host_uid,world_name,minecraft_version,status,max_players,expires_at,created_at")
        .eq("host_uid", user.uid).eq("status", "active").gt("expires_at", nowIso)
        .order("created_at", { ascending: false }).limit(20);
      const { data: invited, error: inviteError } = await db.from("social_world_members")
        .select("session_id,status,created_at,session:social_world_sessions!inner(id,host_uid,world_name,minecraft_version,status,max_players,expires_at,created_at)")
        .eq("user_id", user.uid).in("status", ["invited", "accepted", "joined"]).eq("session.status", "active").gt("session.expires_at", nowIso)
        .order("created_at", { ascending: false }).limit(20);
      if (hostError || inviteError) return fail("Gagal memuat undangan dunia privat", 503);
      const hostIds = [...new Set((invited || []).map((x: any) => x.session?.host_uid).filter(Boolean))];
      const { data: hostProfiles, error: profileError } = hostIds.length
        ? await db.from("social_profiles").select("user_id,username,avatar_url").in("user_id", hostIds)
        : { data: [], error: null };
      if (profileError) return fail("Gagal memuat profil host", 503);
      const names = new Map((hostProfiles || []).map((x: any) => [x.user_id, x]));
      return reply({
        success: true,
        hosted: hosted || [],
        invitations: (invited || []).map((x: any) => ({ ...x.session, host: names.get(x.session?.host_uid) || null })),
      });
    }

    const action = String(body.action || "");
    if (req.method === "POST" && action === "create") {
      const targetUid = String(body.targetUserId || "").trim();
      const worldName = String(body.worldName || "Dunia privat").trim().slice(0, 48);
      const minecraftVersion = String(body.minecraftVersion || "").trim().slice(0, 32);
      if (!targetUid || targetUid === user.uid || !/^[A-Za-z0-9_-]{3,128}$/.test(targetUid)) return fail("Teman tujuan tidak valid");
      if (!(await isFriend(targetUid))) return fail("Undangan dunia hanya dapat dikirim kepada teman yang sudah diterima", 403);
      const { data: active, error: activeError } = await db.from("social_world_sessions")
        .select("id").eq("host_uid", user.uid).eq("status", "active").gt("expires_at", nowIso).limit(3);
      if (activeError) return fail("Gagal memeriksa sesi dunia aktif", 503);
      if ((active || []).length >= 2) return fail("Maksimal dua sesi dunia privat aktif sekaligus", 429);
      const code = makeInviteCode();
      const expiresAt = new Date(Date.now() + 30 * 60 * 1000).toISOString();
      const sessionId = crypto.randomUUID();
      const { error: sessionError } = await db.from("social_world_sessions").insert({
        id: sessionId, host_uid: user.uid, invite_code_hash: await hashInviteCode(code),
        world_name: worldName || "Dunia privat", minecraft_version: minecraftVersion,
        status: "active", max_players: 2, expires_at: expiresAt,
      });
      if (sessionError) return fail("Gagal membuat sesi undangan dunia", 503);
      const { error: memberError } = await db.from("social_world_members").insert({
        session_id: sessionId, user_id: targetUid, status: "invited",
      });
      if (memberError) {
        await db.from("social_world_sessions").delete().eq("id", sessionId).eq("host_uid", user.uid);
        return fail("Gagal menyimpan penerima undangan", 503);
      }
      return reply({ success: true, session: { id: sessionId, worldName, minecraftVersion, expiresAt }, inviteCode: code }, 201);
    }

    if (req.method === "POST" && action === "accept") {
      const code = String(body.inviteCode || "").trim().toUpperCase();
      if (!/^[A-HJ-NP-Z2-9]{10}$/.test(code)) return fail("Kode undangan tidak valid");
      const { data: session, error: sessionError } = await db.from("social_world_sessions")
        .select("id,host_uid,world_name,minecraft_version,status,max_players,expires_at")
        .eq("invite_code_hash", await hashInviteCode(code)).eq("status", "active").gt("expires_at", nowIso).maybeSingle();
      if (sessionError) return fail("Gagal memeriksa kode undangan", 503);
      if (!session) return fail("Kode undangan salah atau sudah kedaluwarsa", 404);
      if (!(await isFriend(session.host_uid))) return fail("Kamu harus berteman dengan host untuk bergabung", 403);
      const { data: membership, error: membershipError } = await db.from("social_world_members")
        .update({ status: "accepted", updated_at: nowIso }).eq("session_id", session.id)
        .eq("user_id", user.uid).eq("status", "invited").select("session_id,status").maybeSingle();
      if (membershipError) return fail("Gagal menerima undangan", 503);
      if (!membership) return fail("Undangan ini bukan ditujukan untuk akunmu atau sudah digunakan", 403);
      return reply({ success: true, session: { id: session.id, hostUid: session.host_uid, worldName: session.world_name, minecraftVersion: session.minecraft_version, maxPlayers: session.max_players, expiresAt: session.expires_at } });
    }

    if (["POST", "PATCH"].includes(req.method) && action === "close") {
      const sessionId = String(body.sessionId || "");
      if (!/^[0-9a-f-]{36}$/i.test(sessionId)) return fail("ID sesi tidak valid");
      const { data, error } = await db.from("social_world_sessions")
        .update({ status: "closed", closed_at: nowIso }).eq("id", sessionId).eq("host_uid", user.uid)
        .eq("status", "active").select("id,status").maybeSingle();
      if (error) return fail("Gagal menutup sesi dunia", 503);
      return data ? reply({ success: true, session: data }) : fail("Sesi tidak ditemukan atau bukan milikmu", 404);
    }

    if (["POST", "PATCH"].includes(req.method) && action === "heartbeat") {
      const sessionId = String(body.sessionId || "");
      if (!/^[0-9a-f-]{36}$/i.test(sessionId)) return fail("ID sesi tidak valid");
      const { data, error } = await db.from("social_world_sessions")
        .update({ expires_at: new Date(Date.now() + 30 * 60 * 1000).toISOString() })
        .eq("id", sessionId).eq("host_uid", user.uid).eq("status", "active").gt("expires_at", nowIso)
        .select("id,expires_at").maybeSingle();
      if (error) return fail("Gagal memperbarui sesi dunia", 503);
      return data ? reply({ success: true, session: data }) : fail("Sesi sudah berakhir atau bukan milikmu", 404);
    }

    if (["POST", "PATCH"].includes(req.method) && action === "revoke") {
      const sessionId = String(body.sessionId || "");
      const targetUid = String(body.targetUserId || "");
      if (!/^[0-9a-f-]{36}$/i.test(sessionId) || !targetUid) return fail("Data pencabutan undangan tidak valid");
      const { data: owner, error: ownerError } = await db.from("social_world_sessions")
        .select("id").eq("id", sessionId).eq("host_uid", user.uid).maybeSingle();
      if (ownerError) return fail("Gagal memeriksa kepemilikan sesi", 503);
      if (!owner) return fail("Sesi tidak ditemukan atau bukan milikmu", 404);
      const { data, error } = await db.from("social_world_members").update({ status: "revoked", updated_at: nowIso })
        .eq("session_id", sessionId).eq("user_id", targetUid).eq("status", "invited")
        .select("session_id,status").maybeSingle();
      if (error) return fail("Gagal mencabut undangan", 503);
      return data ? reply({ success: true }) : fail("Undangan tidak ditemukan", 404);
    }

    return fail("Aksi undangan dunia tidak didukung");
  }

  return fail("Operasi Social TG tidak ditemukan", 404);
}

Deno.serve(async (req: Request) => {
  try {
    return await handle(req);
  } catch (error) {
    console.error("[tg-social-api]", error);
    return fail("Layanan Social TG mengalami gangguan", 503);
  }
});
