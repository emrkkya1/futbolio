# Futbolio manual acceptance checklist

These checks complement fast fixture tests. Do not infer player compatibility or full-match stability from catalog extraction or downloaded segments.

## Desktop → TV over Wi-Fi (about 5 minutes initially)

1. Launch the desktop distributable, choose your private LAN address under Network, save and start.
2. On the TV/phone browser, open the displayed installation URL (or paste the manifest URL into a compatible Stremio installer). Do not replace its IP with localhost; keep the `/a/<token>/` portion.
3. Confirm the three catalogs appear, search works, a channel has metadata/poster, and website choices are labeled external.
4. Select a stream from a reachable provider. Record whether video and audio actually render, not just whether a playlist loads.
5. Try a second device simultaneously. Confirm both keep playing and desktop diagnostics show forwarded bytes.
6. Close the desktop window: playback should continue while the tray process runs. Quit Futbolio: playback should stop after existing player buffers drain.

If step 2 fails: verify the selected interface, private-network firewall rule, VPN, guest-network isolation and the TV's connectivity. No router port forwarding should be needed.

## Android TV → Stremio on the same TV (about 5 minutes initially)

1. Install the APK, open Futbolio, start it, and try Install / open Stremio. If no add-on is installed, record the Stremio version and available install options.
2. Confirm this is the **Futbolio TV** add-on, not Desktop or a legacy add-on. Its URL is exactly `http://127.0.0.1:7000/manifest.json`.
3. Confirm catalogs, search, poster/metadata, stream choices and one actual video/audio playback.
4. Close Futbolio's activity, reopen Stremio, and confirm playback continues. A foreground notification should remain where supported.
5. Reopen Futbolio: bytes should have advanced; Stop should close the service, and Start should restore it.
6. Use the remote/D-pad to reach all controls, edit/disable a provider, refresh, and confirm the catalog/status changes. Restore it afterward. Try import/export only if a document picker is available.

## Longer check (can run while watching a match)

- Watch a full match or comparable long stream; note buffering, service stops and picture/audio failures.
- Exercise standby/wake or a brief Wi-Fi interruption; if a signed URL expires, reselect the stream and check recovery.
- On a low-memory Mi Stick, open another app briefly and return to Stremio. Record whether Android killed Futbolio and whether manual restart recovers.

## Report back

Send desktop OS, TV model/Android version, Stremio version, deployment mode, provider/channel name, the failed checklist step and copied Futbolio diagnostics. **Do not send the desktop access-token URL or full signed upstream stream URLs.**

Live providers may be down or have changed layouts independently. A website fallback is expected for JavaScript-only/protected players; it is not a promise of in-player playback.
