# TG Launcher — Private World Multiplayer

## Goal

Let a TG Launcher player invite an accepted TG friend into a Minecraft Java world over the internet, even when the two Android devices are on different networks. The host runs the normal Minecraft integrated server; TG Launcher must not require the user to operate a separate Minecraft server.

## Important distinction

This is not "zero infrastructure". Game traffic should use a peer-to-peer transport whenever NAT traversal succeeds. A signaling service is still needed to exchange connection offers/candidates, and a TURN relay may be needed when direct peer-to-peer connectivity is blocked. TURN relays packets; it does not run the Minecraft world or simulate a Minecraft server.

## Existing TG Launcher integration points

- Friend identity and accepted-friend relationships already exist in TG Social.
- TG Social requests are authenticated by the existing Firebase ID-token flow and proxied through the TG Admin API to the `tg-social-api` Supabase Edge Function.
- `GameLauncher` starts Minecraft in `GameActivity`, which runs in the dedicated Android `:game` process.
- No existing Minecraft world-hosting tunnel or remote-join transport was found during the initial inspection.

## Proposed architecture

1. **Private session / invitation**
   - Host creates a short-lived private-world session from TG Social or the in-game overlay.
   - Host can invite accepted TG friends; invite code/link is random, short-lived, single-session, and revocable.
   - The backend verifies the caller's Firebase identity and friendship before allowing invite creation or redemption.
   - Session metadata contains only the host UID, world label/version metadata, expiry, and connection state. Never store account passwords or Minecraft access tokens in invitation records.

2. **Signaling**
   - Authenticated signaling exchanges WebRTC offer/answer and ICE candidates.
   - Only the host and explicitly invited/accepted participants may read or write a session's signaling messages.
   - Messages have size limits, timestamps, expiry, replay protection, and rate limits.
   - Signaling is not the Minecraft game-data path.

3. **Minecraft TCP bridge**
   - The host opens the world's built-in LAN server from Minecraft's normal pause menu.
   - TG Launcher detects/receives the local integrated-server TCP port and bridges that local TCP stream to a reliable, ordered WebRTC DataChannel.
   - The joining device exposes a loopback TCP proxy to its Minecraft client; bytes are forwarded through the DataChannel to the host's local integrated server.
   - The bridge must preserve TCP byte ordering and backpressure, handle reconnect/disconnect, and close sockets when the session ends.
   - Java Edition's Minecraft protocol is TCP. A plain UDP-only tunnel or voice-room WebRTC audio path is not sufficient.

4. **Connectivity**
   - Try ICE direct peer-to-peer connectivity first using STUN.
   - Support a TURN relay fallback for restrictive NAT/mobile carrier networks. TURN credentials must be short-lived and issued server-side; never embed a TURN secret in the APK.
   - If no direct or relay path is configured/available, show a clear connection error instead of pretending the session is joinable.

5. **Lifecycle and safety**
   - Host must keep Minecraft running and remain connected; closing the game ends the hosted world.
   - Host can approve/deny join requests, kick a participant, and close the session.
   - Enforce a participant cap, invite expiry, session expiry, rate limits, and per-user authorization.
   - Admin Panel needs a master `worldInvitesEnabled` switch plus safe configuration for STUN/TURN service endpoints. Secrets remain server-side.

## Delivery phases

- **Phase 0 — Architecture and compatibility:** confirm the integrated-server LAN port can be detected reliably on the Android Minecraft runtime; confirm WebRTC DataChannel library/ABI compatibility with the current arm64 build.
- **Phase 1 — Private invitations:** backend session lifecycle, invite codes, friend authorization, expiry, and Admin Panel feature switch. No claim of remote gameplay yet.
- **Phase 2 — Peer transport:** WebRTC signaling, ICE/STUN, DataChannel, and TCP-to-DataChannel bridge.
- **Phase 3 — Relay fallback:** configure and verify TURN fallback without exposing credentials.
- **Phase 4 — End-to-end validation:** two separate Android devices on different networks, host/join, gameplay stability, reconnect, kick, expiry, and failure handling.

## Release and production safeguards

- Develop on `feature/private-world-invites`; do not change the production database, deploy the Edge Function, or alter Vercel production settings until the schema, authorization rules, and transport are reviewed.
- Keep the Android APK release pipeline separate from the Admin Panel build/deploy pipeline.
- A successful CI build is not proof of multiplayer connectivity. Report code, CI, deployment, and real two-device tests separately.


## Release verification
The current Private World implementation is built and checked through the dedicated Android, Admin Panel, and Supabase Edge verification workflow before the standard TG Launcher APK release workflow is run.
