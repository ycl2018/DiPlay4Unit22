# AGENTS.md

Instructions for coding agents that work on DiPlay.

## Checks

Run the CI command from `.github/workflows/android.yml` before you report a change as done:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

Set `ANDROID_HOME` or `local.properties` if Gradle cannot find the SDK.

## Changan S202 adaptation branch

`changan-adapt` is the dedicated compatibility branch for a Changan S202 head unit. Do not
remove a branch-specific workaround merely to make its behavior match upstream. When merging
upstream, review the compatibility rules below before resolving conflicts, changing connection
code, or changing audio focus behavior.

### Reference head unit and app

- Head unit identifiers: `CKBB664384H2A1528`, `S202_ICA`, and `spm8666p1_64_car`.
- Android 9 (API 28). Code paths that work only with newer framework components are not enough.
- ADB runs as `uid=2000(shell)` without root. Do not depend on `iptables`, protected
  `/data/misc/wifi` files such as `hostapd.conf`, or other root-only diagnostics or fixes.
- The head unit runs a vendor-modified `adbd`, not standard AOSP `adbd`. Before it executes each
  `adb shell` command, it requires the validation password `adb36987` as the first line on standard
  input. A normal direct `adb shell "<command>"` invocation can therefore fail, wait, or return no
  useful output even when the device is connected and authorized.
- On the Windows test computer, `adb.exe` is kept in the repository directory and is also on the
  user `PATH`. Feed the password for every shell command with this exact pattern:

  ```powershell
  Write-Output "adb36987" | & adb shell "<command>"
  ```

  Do not silently replace this with standard `adb shell` syntax in scripts or diagnostic steps.

- The vehicle app is the `mobile` module. The initially diagnosed installed build was
  `com.shihab.diplay`, version code 29 / version `0.2.10`, with minSdk 28 and targetSdk 37.
  Current branch versions may be newer. `automotive` is the separate
  `com.shilapi.xcertplay` application and MUST NOT be used as the Changan test APK.
- Debug builds use a different application ID and signing identity, so confirm the APK package,
  variant and signing certificate before attempting an in-place update of the vehicle app.

### Hotspot and wireless host address

The reference hotspot uses interface `ap0` and the `192.168.43.0/24` subnet. Observed interface
state:

- `ap0`: IPv4 `192.168.43.1/24`; IPv6 link-local
  `fe80::50c3:a2ff:fe08:e726/64`; interface index 418; `UP` with `MULTICAST`.
- The observed iPhone hotspot client used IPv4 `192.168.43.41` and also had an IPv6 link-local
  address.
- `wlan0` is commonly `DOWN` / `NO-CARRIER`; do not assume it is the active hotspot interface.

Preserve the IPv4-first contract in `WirelessHostAddress.kt` and `HotspotAddressPolicy.kt`:

1. Select the first usable IPv4 address (not loopback, link-local, any-local or multicast),
   regardless of whether the framework lists IPv6 first.
2. Only when no usable IPv4 exists and the interface index is positive may selection fall back to
   a scoped IPv6 link-local address.
3. For the reference `ap0`, the address advertised to the iPhone MUST be `192.168.43.1`, not an
   unscoped `fe80::` literal.

An iAP2 wireless-control message does not carry an interface scope. iOS cannot connect to an
unscoped IPv6 link-local address, and the failure is silent: CarPlay control Bonjour is not
advertised and AirPlay port 7000 is never connected. Keep the IPv4 preference covered by
`WirelessHostAddressTest` and `HotspotAddressPolicyTest` whenever wireless code is changed or
upstream is merged.

### Audio focus and physical volume keys

The branch intentionally avoids requesting Android audio focus from `CarPlayMediaKeys` on the
reference head unit. When DiPlay owns audio focus on this firmware, the physical volume buttons
are routed to DiPlay. DiPlay does not implement vehicle volume adjustment for those events, so the
driver can no longer change volume. Without the focus request, the head unit continues routing the
buttons to its music-volume path.

Do not restore the upstream audio-focus request solely to make upstream focus-forwarding tests
pass. Tests merged from upstream may assume that `focusRequest` is non-null; adapt such tests to
the branch contract or introduce a proven Changan-specific policy without regressing physical
volume control. Validate physical volume buttons separately from play/pause, next/previous and
CarPlay audio playback.

### Experimental MediaTek decoder tuning

The S202 platform is MediaTek-based. Advanced settings may expose **MTK decoder low latency
(experimental)** only when Android's first decoder for the selected H.264/HEVC format has an
`OMX.MTK.` component name. The published `vdec-*` bridge is an ACodec/OMX extension; detecting a
`c2.mtk.` Codec2 decoder alone is not enough to expose or apply it. The option is off by default and
applies only to the main CarPlay screen at the next connection.

Preserve its ordered fallback contract:

1. Try MTK's legacy `vdec-lowlatency` and `vdec-no-record` (no-reorder) ACodec keys together.
2. If configuration fails, retry with `vdec-lowlatency` only.
3. If that fails, retry the existing standard tuned format without MTK keys.
4. Retain the existing operating-rate and minimal-format fallbacks. A runtime codec failure or
   input stall while an MTK mode is active must lower the maximum MTK mode before recreation.

Never apply these keys to a non-OMX MTK decoder, a mirror or the cluster stream. Do not turn the option
on by default: vendor support varies, no-reorder assumes a real-time stream that does not require
display reordering, and a configuration accepted by a vendor codec can still fail after start.
Diagnostics must record the actual codec name, selected MTK mode and each automatic downgrade.

### Missing AOSP components

This firmware omits some activities normally supplied by a full AOSP system image. Never assume
that a framework intent has a resolver merely because the API exists on Android 9.

- VPN consent UI may be absent. Before launching the intent returned by `VpnService.prepare`,
  verify that it resolves and handle `ActivityNotFoundException`. A missing consent component must
  produce a clear fallback/error instead of a crash or an indefinitely waiting connection flow.
- The DocumentsUI file picker used by `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT` may be
  absent. Diagnostic-report and log export must check intent availability and retain a path that
  does not depend exclusively on the system picker, such as app-local storage followed by ADB or
  another explicitly verified export mechanism.
- Apply the same resolver check to other optional AOSP activities introduced in future changes.
  Test the unavailable-component path on API 28, not only the normal emulator/AOSP path.

### Compatibility review checklist

Before reporting a Changan adaptation or upstream merge as complete, check all of these in
addition to the normal CI command:

1. IPv4 remains preferred for a dual-stack `ap0` and the iPhone receives `192.168.43.1`.
2. No change reintroduces mandatory audio-focus ownership that captures the physical volume keys.
3. API 28 paths do not require missing VPN consent, DocumentsUI or other optional AOSP activities.
4. Diagnostics and proposed fixes do not assume root access.
5. The built/tested APK comes from `mobile`, and its package/variant/signing identity is recorded.
6. Any test whose upstream assumptions intentionally conflict with this branch is called out and
   adapted deliberately; do not hide the failure or undo the vehicle workaround reflexively.

## Adding or moving a setting

The Settings screen is grouped by driver goal, not by implementation.
`SettingsCategory`, `SettingsSection` and `SettingsInformationArchitecture` at the top of
`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` define the groups.
`SettingsLayoutPolicyTest` fails when a section has no category or has more than one.

### Choose the category

Ask which goal the driver has when they look for the setting. Use the first row that fits.

| Category | Put a setting here when it controls… | Examples |
| --- | --- | --- |
| Connection | how the iPhone connects and how DiPlay starts | connection setup, connect on open, start with the car, USB permissions, car hotspot automation, iPhone choice, Android permissions |
| Display | how CarPlay looks on the head-unit screen | day/night mode, picture, size, resolution, frame rate, dock, system bars, multi-window resolution |
| Audio | what the driver hears | media and navigation streams, music buffer |
| Navigation | location and turn-by-turn guidance | location to iPhone, BYD HUD and cluster guidance |
| Vehicle | how CarPlay fits this car and its driver | driving side, wheel keys (Siri, BYD joystick and map zoom), car button, gestures that conflict with the head unit |
| Diagnostics | troubleshooting evidence | diagnostic reports |
| Advanced | experimental, firmware-specific or risky behavior | dashboard map, split screen, screen rotation, side panel, HEVC video, audio focus, audio channel mapping, buffered music, vehicle data |
| Overview | nothing new | see "Overview" below |

A setting goes to Advanced when it is experimental, depends on specific firmware, or is an opt-in that can break sound, video or the connection on some head units.
A control that fixes a common problem and is safe at its default (for example the audio stream choice) stays in its category.
Exception: a gated control that only its own audience sees, and that completes an everyday goal, stays in that goal's category.
Example: auto car hotspot needs ADB but appears only for car-hotspot users, so it is in Connection.

Mark an experimental setting or card with the title suffix ` (experimental)`, never `· experimental`, a dash, or an `Experimental …` prefix.
Each locale MUST use its existing form: `(تجريبي)`, `(experimental)`, `(экспериментально)`, `(експериментально)`, `（实验性）`, `（實驗性）`.

### Add the code

1. To extend an existing card, add the control inside the `filteredSection(...)` block of a card in the correct category.
2. For a new card, add a `SettingsSection` entry and map it to exactly one category in `SettingsInformationArchitecture.sectionsByCategory`.
3. Build the new card with `filteredSection(content, SettingsSection.X, …)` inside `allSettingsSections()`.
   A plain `section(...)` call there MUST NOT be used: it renders on every category page.
4. A setting MUST NOT appear in two categories.
   Overview quick settings are the only duplicates. They MUST use the same persistence as the full control. Overview SHOULD NOT have more than four quick settings.
   A header shortcut MAY duplicate a Display setting when it uses the same persistence.
5. If the change reconnects CarPlay or applies at the next connection, the description MUST say so.
   A setting that only takes effect at the next connection MUST call `markReconnectNeeded()` after it saves.
   This shows the "Reconnect now" bar. It MUST NOT drop a running session without the driver's consent.
   Reconnect at once (`reconnectIfRunning()`) only after a dialog button that says "Apply and reconnect", or when the description says that the change reconnects CarPlay.
6. Add each new string to `common/src/main/res/values/` and to every `values-xx` locale folder.
   New Settings copy SHOULD use the `settings_` prefix. `SettingsTranslationsTest` fails when a `settings_*` string has no translation.
7. A control that opens a choice uses `button()` with the text `"Title · Value"`.
   That form renders as a setting row with the value and a chevron, and Search indexes the title.
8. When you move a control between categories, update `AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem`.

### Overview

Overview holds the connection status, links to the categories, quick settings, About and Language.
Do not add a new setting to Overview. Add it to its category. Then promote it to quick settings only if drivers change it often.

## Settings layout

Spacing, size and shape in the Settings screen MUST come from one place.

1. A gap between blocks MUST use a named dp constant, such as `SETTINGS_BLOCK_GAP_DP`.
   A new literal gap value MUST NOT be added.
2. A size MUST NOT be a pixel constant (`*_PX`). Use dp, and clamp to the available window width.
   Test a menu or panel on a narrow window and on a high-density screen.
3. Controls in one row MUST have the same height.
   Corner radius MUST follow the control height. A new button MUST NOT set its own radius.
4. Every `SettingsCategory` except Overview MUST have a link on Overview.
   `AdaptiveSettingsUiTest.overviewLinksToEveryOtherCategory` checks this.
5. A layout change MUST include compact and full screenshots with a large font scale.
   Add an Arabic (right-to-left) screenshot when the change touches row alignment.
