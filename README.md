# InstaAddict (phone app)

The InstaAddict home-feed task as a normal Android app. One button per task, a check before every run, live logs you can copy, and a record of every run.

**For your own phone only. Use a spare Instagram account. Automation can get an account limited or blocked.**

## Install

1. Open the **Releases** page of this repo on your phone and download `InstaAddict.apk` from the `latest` release.
2. Open it. Android asks to allow installs from your browser: allow, then install.
3. Open the app and follow the **Setup** tab.

Every push to `main` rebuilds the app and replaces that file. Installing a newer one updates the old app (same signing key).

## One-time permission (pick ONE)

- **Option 1, wireless pairing:** Android 11+, Wi-Fi on. Developer options > Wireless debugging > Pair with code, then type the code in the app's notification box.
- **Option 2, cable once:** on a laptop run `adb tcpip 5555` with the phone plugged in. Lasts until the phone restarts.

The app checks this (and the helper tools, Instagram, and the screen) every time you press Start, and tells you what to fix.

## Adding a new task

Tasks live in `app/src/main/java/app/instaaddict/runner/bot/`.

1. Copy `HomeFeedLikeTask.kt`, change `id`, `title`, `description`, `params` and the `run` body.
2. Add the object to `TaskRegistry.all` in `Task.kt`.

It shows up as a new button automatically, with its settings and metrics.

Building blocks are in `Ig.kt` (open Instagram, find hearts, tap, scroll, detect warnings).

## Lessons carried over from the Termux setup

- Open Instagram with `am start` on its real activity name (monkey failed on this phone).
- Instagram must be in English (the task matches the words Home, Like, Liked, Sponsored).
- Scroll slowly (80-140 steps), like a thumb.
- Never leave the screen or switch apps while a task runs. The app holds a wake lock and shows a running notice.
- Stop at any Instagram warning ("try again later", "action blocked").

## Build

GitHub Actions builds the APK (JDK 17, Gradle 8.10.2) and bundles the two uiautomator2 helper APKs from the PyPI wheel. On a failed build it opens an issue with the error lines.
