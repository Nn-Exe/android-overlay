# Overlay Browser

A floating, resizable browser window for Android that sits on top of the home screen, games, or any app. It was built for the Retroid Pocket 6 (Android 13) and works on Android 8 and later.

- **Move**: drag the ⠿ grip, or any empty part of the toolbar.
- **Resize**: drag either bottom corner.
- **Lock**: 🔒 freezes position and size. The window outline turns orange and the resize corners hide.
- **Minimize**: — shrinks the window to a bubble. Tap the bubble to restore; long-press it for Restore / Open in Brave / Close. The page keeps running while minimized, so audio and video keep playing.
- **Close**: ✕, the notification's Close action, or the bubble's long-press menu.

- **Video mode** (▣ on the bar, shown when a page has a video): the window shows only the video player, with YouTube's own controls still working. The bar shrinks to a slim strip, and the window takes the video's exact shape, so there are no black bars.
  - Dragging a corner keeps that shape.
  - When the video changes shape (the next video, an ad, a portrait YouTube Short), the window reshapes on its own.
  - YouTube video pages switch it on by themselves. Turn that off with **⋮ → Auto-fit YouTube videos**.
  - Tap ▣ again to get the full page back. The window returns to where it was before.
  - Video mode remembers its own size and position, separately from normal browsing.

It won't take your gamepad away from the game. The window only takes input focus while you type in the address bar or a text field on the page. Tap outside the window, press Back, or press Go to hand control back.

## Install

Download `OverlayBrowser-1.0.apk` from the [latest release](https://github.com/Nn-Exe/android-overlay/releases/latest).

With USB debugging on, from the folder you saved it to:

```bash
adb install -r OverlayBrowser-1.0.apk
```

Or copy the APK to the device and open it with a file manager (allow "Install unknown apps" for that file manager).

First launch:
1. Tap **Allow display over other apps** and turn it on for Overlay Browser. This is required.
2. Tap **Allow notifications**. This is optional, but it gives you Show/Close controls in the notification shade.
3. Tap **Open floating browser**.

Optional: add the **Float browser** tile in Quick Settings. It shows and hides the window from anywhere, even mid-game.

## Brave

Android doesn't let one app draw another app inside its own window, so Brave itself can't run inside the overlay. Instead:

- The floating window is a built-in Chromium browser (Android System WebView). Brave Search is the default search and home page.
- **⋮ → Open in Brave** sends the current page to the real Brave app.
- **⋮ → Open in Brave (floating window)** opens Brave as a real floating window at the same spot and size as the overlay. This needs freeform windows enabled once:
  1. Settings → About → tap *Build number* 7 times to unlock Developer options.
  2. Developer options → turn on **Enable freeform windows**.
  3. Restart the device.

  If freeform is off, Brave opens full screen instead. If Brave is already open full screen, close it from Recents first; Android reuses the existing window.

The settings screen shows whether Brave is installed and whether freeform windows are on.

## Other features

- **Share → Open in floating browser** works from any app. Overlay Browser also shows up as a choice when opening links.
- **Opacity** (⋮ → Opacity): a slider from 10% (barely there) to 100% (solid), with −/+ steps and 25/50/75/100% presets. The window fades live as you drag. The same slider is on the settings screen, in case you make the window too faint to find.
- Other menu options: Desktop site, Set this page as home, Forward/Reload/Home.
- A page's own fullscreen button (e.g. YouTube's) switches to video mode, and the bar stays usable. To make fullscreen video take the whole screen instead, turn on **⋮ → Fullscreen video uses whole screen**.
- Downloads go to the system Downloads folder.
- If the window ends up under the status bar, navigation bar, or a gesture zone, it moves out from under it so its controls stay reachable. While you type, it rises above the keyboard.

## Known limits

- Android won't show WebView's date/time pickers in an overlay, so `<input type="date">` and similar fields can't be filled in. Dropdown lists (`<select>`) and alert/confirm/prompt dialogs do work, because the app draws its own versions.
- File uploads, camera/mic, and location aren't available to pages.
- Google sign-in may refuse an embedded browser. Use **Open in Brave** for that.
- Android hides third-party overlays on a few security-sensitive screens, such as the Settings app.
- Video mode works by restyling the page. It's tuned for YouTube's mobile site; on other sites it picks the box around the biggest video. If a site's player has no buttons of its own, the browser's standard video controls appear.

## Build

Requires JDK 17 and the Android SDK (`local.properties` points at it).

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk` (minified, signed with this machine's debug key so updates install over each other).

Code map (`app/src/main/java/com/nnexe/overlaybrowser/`):
- `FloatingWindow.kt`: the overlay window, toolbar, gestures, bubble, WebView, dialogs.
- `OverlayService.kt`: foreground service that owns the window, plus the notification.
- `PageScript.kt`: JavaScript injected into pages (keyboard focus handoff, `<select>` replacement, video detection and video mode).
- `Brave.kt`: Brave detection and hand-off, including the freeform launch.
- `MainActivity.kt`: setup screen. `OverlayTileService.kt`: Quick Settings tile. `OpenInOverlayActivity.kt`: share/open-link entry point.
