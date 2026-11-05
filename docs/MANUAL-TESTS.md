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
6. Use the remote/D-pad to reach all controls, provider test details, Diagnostics, and the Times and Scores On/Off control. Confirm the preference survives reopening the app and hides/shows live scores/minutes after Stremio refreshes metadata. Kickoff times and finished-match filtering should not change. Restore the original preference afterward.

## Popup layout and installation guidance

- On phone and TV, verify popup body actions have matching widths and paired footer actions share one row with equal widths/heights. Close must stay in its existing footer slot.
- How to Install should show all five compact steps without scrolling. Copy URL should be full width above the footer's Close button, with no install/open-app action or old TV-version warning. Phone instructions should mention web.stremio.com and the same account; TV instructions should mention the Stremio app and Add Addon.
- Settings should contain Diagnostics, Check for updates, and Times and Scores only. Check touch and D-pad access to both pointed On/Off segments, saving failures, and persistence after process restart. The green active segment should point inward on either side; the divider should mirror when switching between On and Off.
- Provider popups should show a larger source title above plain Available / Catalog / Live Streams rows with bold lavender labels. Available should read Yes (green) / No (white). Catalog counts should read X Events when available; Working must appear only after recognized media bytes were fetched. Unchecked, testing, upcoming-only, and failed states must remain distinct. Test Provider should sit at the bottom, left of Close; all Close buttons should have an icon. No website cards, last-test details, search field, or Copy Report action should remain.

## GitHub updates (phone and TV)

1. With an older, compatibly signed build installed, open Futbolio online. Confirm a newer stable release shows a toast and the update prompt; an equal/older version or prerelease must not be offered.
2. Choose **Download and install**. Confirm progress appears. Rotate the phone during the download: it should continue without restarting or opening the installer twice.
3. If requested, enable **Allow from this source** for Futbolio and return. Confirm Android's installer opens automatically. Deny the permission once and confirm **Install update** can retry afterward.
4. Cancel the installer once, then retry. Complete installation, reopen Futbolio, and verify the version changed and provider/match settings are intact.
5. Cancel a download or interrupt Wi-Fi. Confirm there is no installer for an incomplete download and retry is possible. A debug-signed build must reject a production APK signed with a different key.
6. Open the app offline: the main UI/relay should remain usable without an update error dialog. Use **Settings → Check for updates** to verify manual failures are explained and a current version reports no compatible newer release.
7. Use a TV remote to reach the update prompt's actions and the unknown-source permission/installer screens. Record any vendor-specific installation restrictions.

The app checks only the public GitHub releases endpoint and downloads only after confirmation. Successful installation still requires Android's system confirmation; it is not a silent update.

## Longer check (can run while watching a match)

- Watch a full match or comparable long stream; note buffering, service stops and picture/audio failures.
- Exercise standby/wake or a brief Wi-Fi interruption; if a signed URL expires, reselect the stream and check recovery.
- On a low-memory Mi Stick, open another app briefly and return to Stremio. Record whether Android killed Futbolio and whether manual restart recovers.

## Report back

Send desktop OS, TV model/Android version, Stremio version, deployment mode, provider/channel name, the failed checklist step and copied Futbolio diagnostics. **Do not send the desktop access-token URL or full signed upstream stream URLs.**

Live providers may be down or have changed layouts independently. A website fallback is expected for JavaScript-only/protected players; it is not a promise of in-player playback.
