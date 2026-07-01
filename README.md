# ARIA — Native Android Assistant (v1 foundation)

This replaces the old Termux + Flask web app with a real Android app (APK).
No ADB, no Termux needed to *run* it. Termux is only used once, below, to push
the code to GitHub so GitHub's servers can compile it for you.

## ⚠️ Do this first
Your old `ai_brain.py` had a live Gemini API key hardcoded in it. Go to
[Google AI Studio](https://aistudio.google.com/app/apikey), **delete that key**,
and generate a new one. Use the new one below — never the old one.

## What's actually working in this version (Phase 1)
- Real Android app, installable as a normal APK
- Foreground service that keeps ARIA alive properly (no more Termux getting killed)
- Accessibility Service — this is what replaces ADB for cross-app control
- Battery %, flashlight on/off, volume up/down — all via native Android APIs
- WhatsApp message send (opens chat pre-filled, auto-taps Send if Accessibility is enabled)
- Voice input (tap mic button) and spoken replies (TTS)
- Same "JANI" personality + bilingual Urdu/English brain, now calling Gemini directly from the app

## Not yet built (tell me and I'll add next)
- Always-listening wake word ("Hey JANI") — needs careful battery-friendly design, planned phase 2
- Screenshot capture (needs one-time MediaProjection permission grant)
- Reading notifications
- More app integrations beyond WhatsApp

---

## Step 1 — Get the code onto GitHub (using Termux, no PC needed)

In Termux on your phone:

```bash
pkg install git -y
cd ~
# unzip the ARIA-android-project.zip you downloaded from Claude into ~/ARIA first
cd ARIA
git init
git add .
git commit -m "Initial ARIA native app"
```

Create a new empty repo on github.com (name it `ARIA`) from your phone's browser.
Then, back in Termux, create a GitHub Personal Access Token: GitHub → Settings →
Developer settings → Personal access tokens → generate one with `repo` scope.

```bash
git remote add origin https://github.com/YOUR_USERNAME/ARIA.git
git branch -M main
git push -u origin main
# When prompted for password, paste your Personal Access Token (not your GitHub password)
```

## Step 2 — Add your Gemini API key as a GitHub Secret

On github.com → your ARIA repo → **Settings → Secrets and variables → Actions →
New repository secret**
- Name: `GEMINI_API_KEY`
- Value: your new Gemini key

## Step 3 — Trigger the build

The workflow runs automatically on every push to `main`. Since you just pushed,
it should already be running:

- Go to your repo → **Actions** tab → click the running workflow
- Wait ~3–5 minutes for it to finish
- Scroll down to **Artifacts** → download `ARIA-debug-apk`
- It downloads as a `.zip` — open it to get the actual `.apk` file inside

## Step 4 — Install on your phone

1. Transfer/extract the `.apk` onto your phone if it isn't already there
2. Tap it to install — Android will ask you to allow "install from unknown sources" for your browser/files app once
3. Open ARIA

## Step 5 — Grant permissions inside the app

1. Tap **Start** (starts the foreground service)
2. Tap **Enable Accessibility Permission** → find "ARIA" in the list → turn it on
   (this is what replaces ADB for cross-app control)
3. Allow microphone and camera when prompted
4. Try typing "battery" or tapping 🎤 and saying "turn on flashlight"

---

### If the GitHub Actions build fails
Open the failed run's log and check the `Build debug APK` step — paste the error
back to me and I'll fix the Gradle config. Most likely causes are SDK license
acceptance or a version mismatch, both quick fixes.
