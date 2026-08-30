# VIP- / Nazzilha Plus — Project Reference

## Overview
Social-media video downloader:
- **Backend**: Flask on Render (`https://www.vip-dl.com`)
- **Android**: Native Java app published on Google Play (`com.nazzilhaplus.app`)
- **Website**: served by the same Flask app

---

## Environment Variables (Render Dashboard)

These MUST be set in Render → Environment. Never hardcode them.

| Variable | Description |
|----------|-------------|
| `RAPIDAPI_KEY` | Single key for ALL RapidAPI services |
| `SITE_URL` | `https://www.vip-dl.com` |
| `SECRET_KEY` | Flask session secret |
| `TELEGRAM_BOT_TOKEN` | Telegram bot token |
| `TELEGRAM_ADMIN_IDS` | Comma-separated admin Telegram IDs |
| `UPSTASH_REDIS_REST_URL` | Upstash Redis URL |
| `UPSTASH_REDIS_REST_TOKEN` | Upstash Redis token |
| `ANDROID_CERT_SHA256` | SHA-256 of Android release cert (for request verification) |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Full JSON content of Firebase service account |
| `FCM_PROJECT_ID` | Firebase project ID |
| `WAYL_API_KEY` | Wayl premium subscription API key |
| `ADMIN_CODE` | Admin panel access code |
| `YOUTUBE_COOKIES` | YouTube cookies in Netscape format (optional — currently NOT used, user refused monthly renewal) |

---

## RapidAPI Subscriptions Required

All use the same `RAPIDAPI_KEY`. Subscribe to these on RapidAPI:

| API Name | Host (x-rapidapi-host) | Used For |
|----------|------------------------|----------|
| Social Media Video Downloader | `social-media-video-downloader.p.rapidapi.com` | TikTok, Instagram, Facebook, Twitter, Snapchat, Pinterest |
| YouTube Media Downloader | `youtube-media-downloader.p.rapidapi.com` | YouTube (primary) |
| YouTube Video And Shorts Downloader | `youtube-video-and-shorts-downloader1.p.rapidapi.com` | YouTube (fallback) |
| Auto Download All In One | `auto-download-all-in-one.p.rapidapi.com` | TikTok legacy fallback |

---

## AdMob Ad Unit IDs

Stored in `android-app/app/src/main/res/values/strings.xml`:

| Ad Type | String Name |
|---------|-------------|
| Rewarded Interstitial | `admob_rewarded_interstitial_id` |
| Interstitial | `admob_interstitial_id` |
| App Open | `admob_app_open_id` |
| Banner | `admob_banner_id` |

**Debug builds** use Google's official test IDs (100% fill rate) from:
`android-app/app/src/debug/res/values/strings.xml`

**Important**: AdMob fill rate in Iraq is very low for rewarded ads. The app falls back:
`RewardedInterstitial → Interstitial → beginDownload() immediately (no ad required)`

---

## Download Flow (app.py)

```
POST /api/resolve
  1. SMVD API   → TikTok, Instagram, Facebook, Twitter, Snapchat, Pinterest
  2. YouTube Media Downloader API  → YouTube (proxied URLs, no cookies)
  2b. YouTube Video And Shorts Downloader API → YouTube fallback
  3. yt-dlp fallback → everything else
  → returns {title, thumbnail, platform, formats[]}

Each format URL is routed:
  - Has audio_url (DASH separate streams) → /api/direct-merge?id=SHORT_ID
  - smvd.xyz URL (already proxied, combined) → pass directly
  - YouTube without audio_url → /api/merged-download?src=ORIGINAL_URL
  - Everything else → /api/proxy-download?url=...
```

### Key endpoints
| Endpoint | Purpose |
|----------|---------|
| `POST /api/resolve` | Resolve URL → list of downloadable formats |
| `GET /api/direct-merge?id=ID` | Download video+audio separately, merge with ffmpeg (no cookies) |
| `GET /api/merged-download?src=URL` | yt-dlp download+merge (needs YouTube cookies for YouTube) |
| `GET /api/proxy-download?url=URL` | Proxy a direct CDN URL to the client |
| `GET /api/tiktok-download?src=URL` | TikTok: yt-dlp extract URL then proxy |

### direct-merge URL shortening
YouTube proxied URLs are very long. Passing them in the query string exceeds
gunicorn's 4094-byte request-line limit. Solution: `_store_merge_job(v_url, a_url)`
stores them in `_merge_jobs` dict with a 16-char UUID, returns `/api/direct-merge?id=UUID`.
Jobs expire after 30 minutes.

---

## Android App Key Settings

- **Package**: `com.nazzilhaplus.app`
- **API_BASE**: `https://www.vip-dl.com`
- **Free daily limit**: `FREE_DAILY_LIMIT = 2` (in `MainActivity.java`)
- **Billing product ID**: `premium_monthly`
- **Wayl premium**: verified via `WAYL_API_KEY` + server-side expiry check

### Ad flow (no-cookie workaround)
When user hits daily limit → dialog → "شاهد الإعلان":
1. Try RewardedInterstitial
2. If null → try Interstitial
3. If null → run `beginDownload()` immediately (free pass)

---

## Gunicorn (Procfile)
```
gunicorn app:app --workers 1 --threads 8 --timeout 300 --bind 0.0.0.0:$PORT --limit-request-line 8190
```
`--limit-request-line 8190` is needed because some proxy/CDN URLs are long.

---

## Known Issues & Decisions

| Issue | Decision |
|-------|----------|
| YouTube cookies expire monthly | User refused. Use only proxied RapidAPI URLs (urlAccess=proxied) |
| Instagram yt-dlp fails | Needs cookies. SMVD fallback; if both fail → 422 |
| AdMob low fill rate in Iraq | Interstitial fallback + free pass if no ad |
| YouTube DASH streams video-only | direct-merge endpoint downloads v+a separately and merges with ffmpeg |

---

## Build Instructions

### Server (auto-deploys on push to main)
```bash
git push origin main
```

### Android Debug APK (test ads, no signing needed)
Android Studio → Build → Build APK(s)

### Android Release AAB (for Play Store)
Android Studio → Build → Generate Signed Bundle/APK → Android App Bundle → Release
Upload to Play Store → Internal Testing → Production
