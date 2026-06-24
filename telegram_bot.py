"""
VIP-DL Telegram Bot — Webhook mode
يدعم: تحميل فيديوهات، إشعارات الأدمن، إحصائيات الموقع
"""
import os
import re
import time
import json
import logging
import secrets
import threading
import requests
from pathlib import Path

logging.basicConfig(
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    level=logging.INFO,
)
log = logging.getLogger("vip-bot")

BOT_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "")
ADMIN_CHAT_IDS_RAW = os.environ.get("TELEGRAM_ADMIN_IDS", "")
SITE_URL = os.environ.get("SITE_URL", "https://vip-dl.com").rstrip("/")
ADMIN_IDS: set[int] = set()
if ADMIN_CHAT_IDS_RAW:
    for part in ADMIN_CHAT_IDS_RAW.split(","):
        part = part.strip()
        if part.lstrip("-").isdigit():
            ADMIN_IDS.add(int(part))

_ADMIN_CHANNEL_RAW = os.environ.get("TELEGRAM_ADMIN_CHANNEL", "").strip()
ADMIN_CHANNEL_ID: int | None = int(_ADMIN_CHANNEL_RAW) if _ADMIN_CHANNEL_RAW.lstrip("-").isdigit() else None

API = f"https://api.telegram.org/bot{BOT_TOKEN}"
DOWNLOAD_DIR = Path("downloads")
DOWNLOAD_DIR.mkdir(exist_ok=True)

# ── Ads config (disabled by default — toggle from admin panel) ─────────────────
ADS_ENABLED: list[bool] = [os.environ.get("ADS_ENABLED", "false").lower() == "true"]
ADS_PUBLISHER_ID = os.environ.get("ADS_PUBLISHER_ID", "")
ADS_API_KEY = os.environ.get("ADS_API_KEY", "")

# ── Ad shortener for "watch ad" flow (OuoIO by default) ───────────────────────
AD_SHORTENER_KEY = os.environ.get("AD_SHORTENER_KEY", "")
AD_SHORTENER_SERVICE = os.environ.get("AD_SHORTENER_SERVICE", "ouo")

def make_ad_url(target: str) -> str:
    """Wrap target through an ad-shortener. Falls back to target on failure."""
    if not AD_SHORTENER_KEY:
        return target
    try:
        if AD_SHORTENER_SERVICE == "shrinkme":
            r = requests.get("https://shrinkme.io/api",
                params={"api": AD_SHORTENER_KEY, "url": target}, timeout=10)
            return r.json().get("shortenedUrl", target)
        else:  # ouo (default)
            r = requests.get(f"https://ouo.io/api/{AD_SHORTENER_KEY}",
                params={"s": target}, timeout=10)
            return r.text.strip() or target
    except Exception:
        return target

# ── Telegram API helpers ───────────────────────────────────────────────────────

def _post(method: str, **kwargs) -> dict:
    try:
        r = requests.post(f"{API}/{method}", timeout=60, **kwargs)
        return r.json()
    except Exception as e:
        log.error("Telegram API error (%s): %s", method, e)
        return {}


def send_message(chat_id: int, text: str, parse_mode: str = "HTML", **kwargs) -> dict:
    return _post("sendMessage", json={"chat_id": chat_id, "text": text, "parse_mode": parse_mode, **kwargs})


def send_video(chat_id: int, video_path: str, caption: str = "") -> dict:
    with open(video_path, "rb") as f:
        return _post("sendVideo", data={"chat_id": chat_id, "caption": caption, "parse_mode": "HTML"}, files={"video": f})


def send_audio(chat_id: int, audio_path: str, caption: str = "") -> dict:
    with open(audio_path, "rb") as f:
        return _post("sendAudio", data={"chat_id": chat_id, "caption": caption, "parse_mode": "HTML"}, files={"audio": f})


def send_document(chat_id: int, file_path: str, caption: str = "") -> dict:
    with open(file_path, "rb") as f:
        return _post("sendDocument", data={"chat_id": chat_id, "caption": caption, "parse_mode": "HTML"}, files={"document": f})


def answer_callback(callback_query_id: str, text: str = "") -> dict:
    return _post("answerCallbackQuery", json={"callback_query_id": callback_query_id, "text": text})


def set_webhook(webhook_url: str) -> bool:
    result = _post("setWebhook", json={"url": webhook_url, "max_connections": 10, "drop_pending_updates": True})
    ok = result.get("ok", False)
    if ok:
        log.info("Webhook set: %s", webhook_url)
    else:
        log.error("Failed to set webhook: %s", result)
    return ok


# ── Site API helpers ───────────────────────────────────────────────────────────

def admin_api(endpoint: str, method: str = "GET") -> dict:
    headers = {"X-Bot-Token": BOT_TOKEN}
    try:
        fn = requests.post if method == "POST" else requests.get
        r = fn(f"{SITE_URL}/bot-admin/{endpoint}", headers=headers, timeout=30)
        return r.json()
    except Exception as e:
        return {"error": str(e)}


def site_info(url: str) -> dict:
    try:
        r = requests.post(f"{SITE_URL}/api/info", json={"url": url}, timeout=60)
        return r.json()
    except Exception as e:
        return {"error": str(e)}


def site_download(url: str, format_id: str, cache_id: str = "") -> dict:
    try:
        r = requests.post(
            f"{SITE_URL}/api/download",
            json={"url": url, "format_id": format_id, "cache_id": cache_id},
            timeout=15,
        )
        return r.json()
    except Exception as e:
        return {"error": str(e)}


def site_progress(task_id: str) -> dict:
    try:
        r = requests.get(f"{SITE_URL}/api/progress/{task_id}", timeout=10)
        return r.json()
    except Exception as e:
        return {"status": "error", "error": str(e)}


def site_stats() -> dict:
    try:
        r = requests.get(f"{SITE_URL}/api/public-stats", timeout=10)
        return r.json()
    except Exception as e:
        return {"error": str(e)}


# ── Ad link shortener ─────────────────────────────────────────────────────────

def shorten_url(target_url: str) -> str:
    """Wrap target_url through AdFly. Returns target_url unchanged on failure."""
    if not ADS_API_KEY or not ADS_PUBLISHER_ID:
        return target_url
    try:
        r = requests.get(
            "https://api.adf.ly/api.php",
            params={
                "key": ADS_API_KEY,
                "uid": ADS_PUBLISHER_ID,
                "advert_type": "int",
                "url": target_url,
            },
            timeout=10,
        )
        shortened = r.text.strip()
        return shortened if shortened.startswith("http") else target_url
    except Exception:
        return target_url


# ── URL detection ──────────────────────────────────────────────────────────────

URL_PATTERN = re.compile(r"https?://\S+", re.IGNORECASE)

PLATFORM_NAMES = {
    "tiktok": "TikTok",
    "instagram": "Instagram",
    "facebook": "Facebook",
    "twitter": "Twitter / X",
    "x.com": "Twitter / X",
    "youtube": "YouTube",
    "pinterest": "Pinterest",
}


def detect_platform(url: str) -> str:
    url_lower = url.lower()
    for key, name in PLATFORM_NAMES.items():
        if key in url_lower:
            return name
    return "موقع غير معروف"


# ── Upstash Redis persistence ──────────────────────────────────────────────────
_UPSTASH_URL   = os.environ.get("UPSTASH_REDIS_REST_URL", "").rstrip("/")
_UPSTASH_TOKEN = os.environ.get("UPSTASH_REDIS_REST_TOKEN", "")

def _redis_get(key: str):
    if not _UPSTASH_URL:
        return None
    try:
        r = requests.post(
            _UPSTASH_URL,
            headers={"Authorization": f"Bearer {_UPSTASH_TOKEN}", "Content-Type": "application/json"},
            json=["GET", key],
            timeout=10,
        )
        val = r.json().get("result")
        return json.loads(val) if val else None
    except Exception as e:
        log.warning("Redis GET %s: %s", key, e)
        return None

def _redis_set(key: str, value) -> None:
    if not _UPSTASH_URL:
        return
    try:
        requests.post(
            _UPSTASH_URL,
            headers={"Authorization": f"Bearer {_UPSTASH_TOKEN}", "Content-Type": "application/json"},
            json=["SET", key, json.dumps(value, ensure_ascii=False)],
            timeout=10,
        )
    except Exception as e:
        log.warning("Redis SET %s: %s", key, e)

def _redis_sadd(key: str, member: str) -> None:
    if not _UPSTASH_URL:
        return
    try:
        requests.post(
            _UPSTASH_URL,
            headers={"Authorization": f"Bearer {_UPSTASH_TOKEN}", "Content-Type": "application/json"},
            json=["SADD", key, member],
            timeout=10,
        )
    except Exception as e:
        log.warning("Redis SADD %s: %s", key, e)

def _redis_sismember(key: str, member: str) -> bool:
    if not _UPSTASH_URL:
        return False
    try:
        r = requests.post(
            _UPSTASH_URL,
            headers={"Authorization": f"Bearer {_UPSTASH_TOKEN}", "Content-Type": "application/json"},
            json=["SISMEMBER", key, member],
            timeout=10,
        )
        return bool(r.json().get("result", 0))
    except Exception as e:
        log.warning("Redis SISMEMBER %s: %s", key, e)
        return False

def _redis_srem(key: str, member: str) -> None:
    if not _UPSTASH_URL:
        return
    try:
        requests.post(
            _UPSTASH_URL,
            headers={"Authorization": f"Bearer {_UPSTASH_TOKEN}", "Content-Type": "application/json"},
            json=["SREM", key, member],
            timeout=10,
        )
    except Exception as e:
        log.warning("Redis SREM %s: %s", key, e)

# ── Local file fallback ────────────────────────────────────────────────────────
DATA_DIR = Path("bot_data")
DATA_DIR.mkdir(exist_ok=True)

_PREMIUM_FILE   = DATA_DIR / "premium_users.json"
_BLOCKED_FILE   = DATA_DIR / "blocked_users.json"
_DOWNLOADS_FILE = DATA_DIR / "user_downloads.json"
_WELCOME_FILE   = DATA_DIR / "custom_welcome.json"
_HISTORY_FILE   = DATA_DIR / "user_history.json"
_CONFIG_FILE    = DATA_DIR / "bot_config.json"

def _load_json(path: Path, default):
    try:
        if path.exists():
            return json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        log.warning("Failed to load %s: %s", path, e)
    return default

def _save_json(path: Path, data) -> None:
    try:
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    except Exception as e:
        log.warning("Failed to save %s: %s", path, e)

def _load(redis_key: str, file_path: Path, default):
    val = _redis_get(redis_key)
    if val is not None:
        return val
    return _load_json(file_path, default)

def _save(redis_key: str, file_path: Path, data) -> None:
    _redis_set(redis_key, data)
    _save_json(file_path, data)


# ── User state ─────────────────────────────────────────────────────────────────
pending: dict[int, dict] = {}
known_users: dict[int, dict] = {}   # uid -> {"name": str, "username": str|None}
ad_verif_tokens: dict[str, int] = {}  # token -> chat_id (cleared once verified)
active_downloads: set[int] = set()  # chat_ids with a download in progress
_app_sessions_local: set[int] = set()  # fallback when Redis unavailable

def _session_add(chat_id: int) -> None:
    _app_sessions_local.add(chat_id)
    _redis_sadd("app_sessions", str(chat_id))

def _session_has(chat_id: int) -> bool:
    if _redis_sismember("app_sessions", str(chat_id)):
        return True
    return chat_id in _app_sessions_local

def _session_remove(chat_id: int) -> None:
    _app_sessions_local.discard(chat_id)
    _redis_srem("app_sessions", str(chat_id))

_premium_raw    = _load("premium_users", _PREMIUM_FILE, {})
premium_users: dict[int, str]  = {int(k): v for k, v in _premium_raw.items()}

_blocked_raw    = _load("blocked_users", _BLOCKED_FILE, [])
blocked_users: set[int]        = set(_blocked_raw)

_downloads_raw  = _load("user_downloads", _DOWNLOADS_FILE, {})
user_downloads: dict[int, dict] = {int(k): v for k, v in _downloads_raw.items()}

_welcome_raw    = _load("custom_welcome", _WELCOME_FILE, [])
custom_welcome: list[str]      = _welcome_raw

_history_raw    = _load("user_history", _HISTORY_FILE, {})
user_history: dict[int, list]  = {int(k): v for k, v in _history_raw.items()}

_config_raw     = _load("bot_config", _CONFIG_FILE, {})
_daily_limit: list[int] = [int(_config_raw.get("daily_limit", 50))]


def _save_premium():
    _save("premium_users", _PREMIUM_FILE, {str(k): v for k, v in premium_users.items()})

def _save_blocked():
    _save("blocked_users", _BLOCKED_FILE, list(blocked_users))

def _save_downloads():
    _save("user_downloads", _DOWNLOADS_FILE, {str(k): v for k, v in user_downloads.items()})

def _save_welcome():
    _save("custom_welcome", _WELCOME_FILE, custom_welcome)

def _save_history():
    _save("user_history", _HISTORY_FILE, {str(k): v for k, v in user_history.items()})

def _save_config():
    _save("bot_config", _CONFIG_FILE, {"daily_limit": _daily_limit[0]})

def _add_to_history(chat_id: int, url: str, title: str, platform: str):
    hist = user_history.get(chat_id, [])
    hist = [h for h in hist if h.get("url") != url]
    hist.insert(0, {"url": url, "title": title, "platform": platform})
    user_history[chat_id] = hist[:5]
    _save_history()


def is_premium(chat_id: int) -> bool:
    exp = premium_users.get(chat_id)
    if not exp:
        return False
    if exp == "lifetime":
        return True
    import datetime
    try:
        return datetime.datetime.now() < datetime.datetime.strptime(exp, "%Y-%m-%d %H:%M")
    except Exception:
        return False


def check_download_limit(chat_id: int) -> bool:
    """Returns True if allowed, False if daily limit exceeded."""
    if is_premium(chat_id):
        return True
    import datetime
    today = datetime.date.today().isoformat()
    data = user_downloads.setdefault(chat_id, {"date": today, "count": 0})
    if data.get("date") != today:
        data["date"] = today
        data["count"] = 0
    if data["count"] >= _daily_limit[0]:
        return False
    data["count"] += 1
    _save_downloads()
    return True


def redeem_code(chat_id: int, code: str) -> dict:
    try:
        r = requests.post(
            f"{SITE_URL}/bot-admin/redeem-code",
            headers={"X-Bot-Token": BOT_TOKEN},
            json={"code": code.upper(), "chat_id": chat_id},
            timeout=15,
        )
        return r.json()
    except Exception as e:
        return {"error": str(e)}


# ── Message handlers ───────────────────────────────────────────────────────────

HELP_TEXT = """🤖 <b>بوت نزلها بلس للتحميل</b>

أرسل لي رابط الفيديو مباشرةً وسأحمله لك!

<b>المنصات المدعومة:</b>
• TikTok  |  Instagram  |  Facebook
• Twitter/X  |  Pinterest  |  وأكثر

<b>الأوامر:</b>
/start — رسالة الترحيب
/help — هذه القائمة
/platforms — المنصات المدعومة
/stats — إحصائيات الموقع
/site — رابط الموقع
/share — شارك البوت مع أصدقائك
/redeem — تفعيل كود بريميوم
/status — حالة اشتراكك
/history — آخر تحميلاتك

📎 فقط الصق الرابط وأنا أتولى الباقي!

🆓 المجاني: <b>5 تحميلات/يوم</b>
💎 البريميوم: <b>غير محدود</b>"""


MAIN_KEYBOARD = {
    "keyboard": [
        [{"text": "📲 حمّل فيديو"}],
        [{"text": "📊 الإحصائيات"}, {"text": "🔥 الأكثر تحميلاً"}],
        [{"text": "📣 شارك البوت"}, {"text": "ℹ️ المساعدة"}],
        [{"text": "💎 بريميوم"}, {"text": "🌐 الموقع"}],
    ],
    "resize_keyboard": True,
    "persistent": True,
}

PLATFORMS_KEYBOARD = {
    "inline_keyboard": [
        [
            {"text": "🎵 TikTok", "callback_data": "platform:tiktok"},
            {"text": "📸 Instagram", "callback_data": "platform:instagram"},
        ],
        [
            {"text": "📘 Facebook", "callback_data": "platform:facebook"},
            {"text": "🐦 Twitter / X", "callback_data": "platform:twitter"},
        ],
        [
            {"text": "📌 Pinterest", "callback_data": "platform:pinterest"},
            {"text": "🌐 أخرى", "callback_data": "platform:other"},
        ],
    ]
}

PLATFORM_LABELS = {
    "tiktok": "🎵 TikTok",
    "instagram": "📸 Instagram",
    "facebook": "📘 Facebook",
    "twitter": "🐦 Twitter / X",
    "pinterest": "📌 Pinterest",
    "other": "🌐 منصة أخرى",
}


def handle_start(chat_id: int, first_name: str, param: str = ""):
    if param:
        try:
            r = requests.get(f"{SITE_URL}/api/url-token/{param}", timeout=10)
            if r.status_code == 200:
                url = r.json().get("url", "")
                if url and URL_PATTERN.search(url):
                    _session_add(chat_id)  # منح جلسة تحميل واحدة
                    send_message(chat_id, f"مرحباً {first_name}! 🎯\nجاري تحميل الفيديو تلقائياً...")
                    threading.Thread(target=handle_url, args=(chat_id, url, first_name), daemon=True).start()
                    return
        except Exception:
            pass

    if custom_welcome:
        text = custom_welcome[0].replace("{name}", first_name)
    else:
        text = (
            f"مرحباً {first_name} 🌟\n\n"
            "نزّل أي فيديو تريده بضغطة واحدة!\n"
            "من تيك توك، إنستغرام، فيسبوك وأكثر 🎯\n\n"
            "أرسل الرابط الآن وجرّب بنفسك 👇"
        )
    if chat_id in ADMIN_IDS:
        admin_kb = {
            "keyboard": [
                [{"text": "📲 حمّل فيديو"}],
                [{"text": "📊 الإحصائيات"}, {"text": "🔥 الأكثر تحميلاً"}],
                [{"text": "📣 شارك البوت"}, {"text": "ℹ️ المساعدة"}],
                [{"text": "💎 بريميوم"}, {"text": "🌐 الموقع"}],
                [{"text": "🛠️ لوحة التحكم"}],
            ],
            "resize_keyboard": True,
            "persistent": True,
        }
        send_message(chat_id, text, reply_markup=admin_kb)
    else:
        send_message(chat_id, text, reply_markup=MAIN_KEYBOARD)


def handle_help(chat_id: int):
    send_message(chat_id, HELP_TEXT)


def handle_stats(chat_id: int):
    data = site_stats()
    if "error" in data:
        send_message(chat_id, "⚠️ تعذّر جلب الإحصائيات، حاول لاحقاً.")
        return

    platforms = data.get("platform_counts", {})
    platform_lines = "\n".join(
        f"  • {k}: <b>{v:,}</b>" for k, v in platforms.items() if v > 0
    ) or "  لا يوجد بيانات"

    text = (
        "📊 <b>إحصائيات VIP-DL</b>\n\n"
        f"⬇️ إجمالي التحميلات: <b>{data.get('total_downloads', 0):,}</b>\n"
        f"📅 تحميلات اليوم: <b>{data.get('today_downloads', 0):,}</b>\n"
        f"❌ فاشلة: <b>{data.get('failed_downloads', 0):,}</b>\n\n"
        f"📱 حسب المنصة:\n{platform_lines}\n\n"
        f"🌐 <a href='{SITE_URL}'>زيارة الموقع</a>"
    )
    send_message(chat_id, text, disable_web_page_preview=True)


def handle_site(chat_id: int):
    send_message(chat_id, f"🌐 موقع VIP-DL:\n{SITE_URL}")


def handle_platforms(chat_id: int):
    text = (
        "📱 <b>المنصات المدعومة:</b>\n\n"
        "🎵 TikTok\n"
        "📸 Instagram\n"
        "📘 Facebook\n"
        "🐦 Twitter / X\n"
        "📌 Pinterest\n"
        "➕ والمئات من المواقع الأخرى!\n\n"
        "فقط أرسل الرابط وأنا أتولى الباقي 😉"
    )
    send_message(chat_id, text)


def handle_top(chat_id: int):
    data = site_stats()
    if "error" in data:
        send_message(chat_id, "⚠️ تعذّر جلب البيانات، حاول لاحقاً.")
        return

    platforms = data.get("platform_counts", {})
    sorted_p = sorted(platforms.items(), key=lambda x: x[1], reverse=True)

    lines = []
    medals = ["🥇", "🥈", "🥉", "4️⃣", "5️⃣"]
    for i, (name, count) in enumerate(sorted_p[:5]):
        if count > 0:
            lines.append(f"{medals[i]} {name}: <b>{count:,}</b> تحميل")

    text = (
        "🔥 <b>الأكثر تحميلاً</b>\n\n"
        + ("\n".join(lines) or "لا يوجد بيانات بعد")
        + f"\n\n📊 الإجمالي: <b>{data.get('total_downloads', 0):,}</b>"
    )
    send_message(chat_id, text)


ADMIN_KEYBOARD = {
    "inline_keyboard": [
        [{"text": "📊 الإحصائيات", "callback_data": "adm:stats"}, {"text": "👁️ الزوار", "callback_data": "adm:visitors"}],
        [{"text": "⚡ حالة السيرفر", "callback_data": "adm:health"}, {"text": "🎟️ الأكواد", "callback_data": "adm:codes"}],
        [{"text": "🎁 كود جديد 30 يوم", "callback_data": "adm:gencode:30"}, {"text": "🎁 كود 7 أيام", "callback_data": "adm:gencode:7"}],
        [{"text": "🗑️ تنظيف الملفات", "callback_data": "adm:clear"}, {"text": "🔄 تحديث yt-dlp", "callback_data": "adm:update"}],
        [{"text": "📢 رسالة جماعية", "callback_data": "adm:broadcast"}, {"text": "👥 المستخدمون", "callback_data": "adm:users"}],
        [{"text": "🚫 حظر مستخدم", "callback_data": "adm:block"}, {"text": "✅ رفع حظر", "callback_data": "adm:unblock"}],
        [{"text": "✏️ تعديل رسالة الترحيب", "callback_data": "adm:setwelcome"}],
        [{"text": "💰 الإعلانات: مُوقف ⚫", "callback_data": "adm:toggleads"}],
        [{"text": "⚙️ الحد اليومي: 3 تحميلات", "callback_data": "adm:setlimit"}],
        [{"text": "❌ إغلاق", "callback_data": "adm:close"}],
    ]
}


def _build_admin_keyboard() -> dict:
    ads_label = "💰 الإعلانات: مُفعَّل 🟢" if ADS_ENABLED[0] else "💰 الإعلانات: مُوقف ⚫"
    limit_label = f"⚙️ الحد اليومي: {_daily_limit[0]} تحميلات"
    kb = [row[:] for row in ADMIN_KEYBOARD["inline_keyboard"]]
    for i, row in enumerate(kb):
        for j, btn in enumerate(row):
            if btn.get("callback_data") == "adm:toggleads":
                kb[i][j] = {"text": ads_label, "callback_data": "adm:toggleads"}
            elif btn.get("callback_data") == "adm:setlimit":
                kb[i][j] = {"text": limit_label, "callback_data": "adm:setlimit"}
    return {"inline_keyboard": kb}


def handle_admin_panel(chat_id: int):
    send_message(chat_id, "🛠️ <b>لوحة تحكم الأدمن</b>\n\nاختر الإجراء:", reply_markup=_build_admin_keyboard())


def handle_admin_callback(chat_id: int, cq_id: str, action: str):
    answer_callback(cq_id)

    if action == "close":
        send_message(chat_id, "✅ تم إغلاق لوحة التحكم.")
        return

    if action == "stats":
        data = admin_api("stats")
        if "error" in data:
            send_message(chat_id, f"❌ خطأ: {data['error']}")
            return
        platforms = data.get("platform_counts", {})
        platform_lines = "\n".join(f"  • {k}: <b>{v:,}</b>" for k, v in platforms.items() if v > 0) or "  لا بيانات"
        errors = data.get("recent_errors", [])
        err_lines = "\n".join(f"  ⚠️ {e.get('platform','?')}: {str(e.get('error',''))[:50]}" for e in errors) or "  لا أخطاء"
        text = (
            "📊 <b>إحصائيات مفصّلة</b>\n\n"
            f"⬇️ إجمالي: <b>{data.get('total_downloads',0):,}</b>\n"
            f"📅 اليوم: <b>{data.get('today_downloads',0):,}</b>\n"
            f"❌ فاشلة: <b>{data.get('failed_downloads',0):,}</b>\n"
            f"⚡ نشطة الآن: <b>{data.get('active_tasks',0)}</b>\n\n"
            f"💾 التخزين: <b>{data.get('storage_mb',0)} MB</b> ({data.get('temp_files',0)} ملف)\n"
            f"🤖 yt-dlp: <b>{data.get('ytdlp_version','?')}</b>\n"
            f"🕐 الوقت: {data.get('server_time','')}\n\n"
            f"📱 المنصات:\n{platform_lines}\n\n"
            f"🔴 آخر الأخطاء:\n{err_lines}"
        )
        send_message(chat_id, text, reply_markup=_build_admin_keyboard())
        return

    if action == "visitors":
        data = admin_api("visitor-stats")
        if "error" in data:
            send_message(chat_id, f"❌ خطأ: {data['error']}")
            return
        text = (
            "👁️ <b>إحصائيات الزوار</b>\n\n"
            f"📅 اليوم: <b>{data.get('today',0):,}</b> زيارة\n"
            f"📆 أمس: <b>{data.get('yesterday',0):,}</b> زيارة\n"
            f"📊 الأسبوع: <b>{data.get('week',0):,}</b> زيارة"
        )
        send_message(chat_id, text, reply_markup=_build_admin_keyboard())
        return

    if action == "health":
        data = admin_api("health")
        if "error" in data:
            send_message(chat_id, f"❌ خطأ: {data['error']}")
            return
        idle = data.get('idle_sec', 0)
        idle_str = f"{idle // 60}د {idle % 60}ث"
        text = (
            "⚡ <b>حالة السيرفر</b>\n\n"
            f"🟢 الحالة: <b>Online</b>\n"
            f"⏱ وقت التشغيل: <b>{data.get('uptime','?')}</b>\n"
            f"💤 غير نشط منذ: <b>{idle_str}</b>\n"
            f"💾 التخزين: <b>{data.get('storage_mb',0)} MB</b>\n"
            f"⚡ مهام نشطة: <b>{data.get('active_tasks',0)}</b>"
        )
        send_message(chat_id, text, reply_markup=_build_admin_keyboard())
        return

    if action == "codes":
        data = admin_api("codes")
        if "error" in data:
            send_message(chat_id, f"❌ خطأ: {data['error']}")
            return
        recent_lines = "\n".join(
            f"  🎟 <code>{c['code']}</code> — {'✅ مستخدم' if c['used'] else '🔓 متاح'} ({c.get('days',0)} يوم)"
            for c in data.get("recent", [])
        ) or "  لا أكواد"
        text = (
            "🎟️ <b>أكواد البريميوم</b>\n\n"
            f"📦 الإجمالي: <b>{data.get('total',0)}</b>\n"
            f"✅ نشطة: <b>{data.get('active',0)}</b>\n"
            f"🔓 غير مستخدمة: <b>{data.get('unused',0)}</b>\n"
            f"❌ منتهية: <b>{data.get('expired',0)}</b>\n\n"
            f"آخر الأكواد:\n{recent_lines}"
        )
        send_message(chat_id, text, reply_markup=_build_admin_keyboard())
        return

    if action.startswith("gencode:"):
        days = int(action.split(":")[1])
        data = admin_api(f"generate-code", "POST")
        # pass days via json
        try:
            import requests as req
            r = req.post(f"{SITE_URL}/bot-admin/generate-code",
                        headers={"X-Bot-Token": BOT_TOKEN},
                        json={"days": days}, timeout=15)
            data = r.json()
        except Exception as e:
            data = {"error": str(e)}
        if "error" in data:
            send_message(chat_id, f"❌ خطأ: {data['error']}")
        else:
            send_message(chat_id,
                f"🎁 <b>كود جديد ({days} يوم)</b>\n\n"
                f"<code>{data.get('code','')}</code>\n\n"
                f"انسخ الكود وأعطه للمستخدم.",
                reply_markup=_build_admin_keyboard())
        return

    if action == "clear":
        data = admin_api("clear-files", "POST")
        if "error" in data:
            send_message(chat_id, f"❌ خطأ: {data['error']}")
        else:
            send_message(chat_id, f"✅ تم حذف <b>{data.get('removed',0)}</b> ملف مؤقت.", reply_markup=_build_admin_keyboard())
        return

    if action == "update":
        send_message(chat_id, "⏳ جاري تحديث yt-dlp في الخلفية...")
        admin_api("update-ytdlp", "POST")
        send_message(chat_id, "✅ بدأ التحديث — قد يستغرق دقيقة.", reply_markup=_build_admin_keyboard())
        return

    if action == "users":
        count = len(known_users)
        blocked_count = len(blocked_users)
        user_list = "\n".join(
            f"  • {(info.get('name','?') if isinstance(info,dict) else info)}"
            f"{(' (@'+info.get('username')+')' if isinstance(info,dict) and info.get('username') else '')}"
            f" <code>{uid}</code>"
            for uid, info in list(known_users.items())[-10:]
        )
        text = (
            f"👥 <b>المستخدمون</b>\n\n"
            f"إجمالي: <b>{count}</b> | محظور: <b>{blocked_count}</b>\n\n"
            f"آخر 10:\n{user_list or 'لا يوجد بعد'}"
        )
        send_message(chat_id, text, reply_markup=_build_admin_keyboard())
        return

    if action == "block":
        pending[chat_id] = {"waiting_block": True}
        send_message(chat_id, "🚫 أرسل Chat ID المستخدم الذي تريد حظره:")
        return

    if action == "unblock":
        pending[chat_id] = {"waiting_unblock": True}
        send_message(chat_id, "✅ أرسل Chat ID المستخدم الذي تريد رفع حظره:")
        return

    if action == "setwelcome":
        pending[chat_id] = {"waiting_welcome": True}
        send_message(chat_id, "✏️ أرسل نص رسالة الترحيب الجديدة:\n\n(أرسل /reset لإعادتها للافتراضي)")
        return

    if action == "toggleads":
        ADS_ENABLED[0] = not ADS_ENABLED[0]
        status = "مُفعَّل 🟢" if ADS_ENABLED[0] else "مُوقف ⚫"
        send_message(chat_id, f"💰 <b>وضع الإعلانات: {status}</b>", reply_markup=_build_admin_keyboard())
        return

    if action == "broadcast":
        pending[chat_id] = {"waiting_broadcast": True}
        send_message(chat_id, "📢 أرسل الرسالة التي تريد إرسالها لجميع المستخدمين:")
        return

    if action == "setlimit":
        pending[chat_id] = {"waiting_setlimit": True}
        send_message(chat_id, f"⚙️ الحد اليومي الحالي: <b>{_daily_limit[0]}</b> تحميلات\n\nأرسل الرقم الجديد (1-50):")
        return


def handle_download_menu(chat_id: int):
    _post("sendMessage", json={
        "chat_id": chat_id,
        "text": "🎬 <b>اختر المنصة التي تريد التحميل منها:</b>",
        "parse_mode": "HTML",
        "reply_markup": PLATFORMS_KEYBOARD,
    })


def handle_platform_selected(chat_id: int, callback_query_id: str, platform: str):
    answer_callback(callback_query_id, "✅ تم الاختيار")
    label = PLATFORM_LABELS.get(platform, "🌐 منصة أخرى")
    pending[chat_id] = {"waiting_url": True, "platform": platform}
    send_message(chat_id, f"👍 اخترت <b>{label}</b>\n\nأرسل الرابط الآن 👇")


def handle_share(chat_id: int):
    text = (
        "📣 <b>شارك بوت نزلها بلس مع أصدقائك!</b>\n\n"
        "🔗 رابط البوت:\n"
        "https://t.me/nazzilhaplus_bot\n\n"
        "انسخ الرسالة أدناه وأرسلها لأصدقائك 👇\n\n"
        "┄┄┄┄┄┄┄┄┄┄┄┄\n"
        "🎬 جرّب بوت <b>نزلها بلس</b>!\n"
        "نزّل أي فيديو من تيك توك، إنستغرام، فيسبوك وأكثر — مجاناً وبدون تسجيل ✨\n"
        "👉 https://t.me/nazzilhaplus_bot"
    )
    send_message(chat_id, text, disable_web_page_preview=True)


SUBSCRIBE_PLANS = [
    {"days": 7,  "stars": 50,  "label": "7 أيام"},
    {"days": 30, "stars": 150, "label": "30 يوم"},
    {"days": 90, "stars": 500, "label": "90 يوم"},
]

SUBSCRIBE_KEYBOARD = {
    "inline_keyboard": [
        [{"text": f"⭐ {p['stars']} Stars — {p['label']}", "callback_data": f"sub:{p['days']}"}]
        for p in SUBSCRIBE_PLANS
    ] + [[{"text": "🔑 عندي كود — /redeem", "callback_data": "sub:code"}]]
}


def send_invoice(chat_id: int, days: int, stars: int, label: str):
    return _post("sendInvoice", json={
        "chat_id": chat_id,
        "title": f"💎 بريميوم نزلها بلس — {label}",
        "description": f"تحميلات غير محدودة لمدة {label} 🚀",
        "payload": f"premium_{days}_{chat_id}",
        "currency": "XTR",
        "prices": [{"label": label, "amount": stars}],
    })


def handle_subscribe_menu(chat_id: int):
    send_message(
        chat_id,
        "💎 <b>ترقية للبريميوم</b>\n\n"
        "اختر الباقة المناسبة:\n\n"
        "⭐ الدفع عبر Telegram Stars (مدمج داخل التطبيق)\n"
        "✅ تفعيل فوري بعد الدفع",
        reply_markup=SUBSCRIBE_KEYBOARD,
    )


def handle_subscribe_callback(chat_id: int, cq_id: str, plan: str):
    answer_callback(cq_id)
    if plan == "menu":
        handle_subscribe_menu(chat_id)
        return
    if plan == "code":
        send_message(chat_id, "🔑 أرسل كودك:\n/redeem XXXX-XXXXXXXX")
        return
    try:
        days = int(plan)
    except ValueError:
        return
    p = next((x for x in SUBSCRIBE_PLANS if x["days"] == days), None)
    if not p:
        return
    send_invoice(chat_id, p["days"], p["stars"], p["label"])


def handle_adwatch_start(chat_id: int, cq_id: str):
    answer_callback(cq_id)
    data = pending.get(chat_id, {})
    url = data.get("ad_pending_url", "")
    if not url:
        send_message(chat_id, "⚠️ انتهت الجلسة، أرسل الرابط مجدداً.")
        return
    token = secrets.token_urlsafe(20)
    pending[chat_id]["ad_token"] = token
    pending[chat_id]["ad_verified"] = False
    ad_verif_tokens[token] = chat_id
    watch_url = f"{SITE_URL}/watch-ad/{token}"
    send_message(
        chat_id,
        "📺 <b>شاهد إعلاناً قصيراً واحصل على تحميل مجاني</b>\n\n"
        f'👉 <a href="{watch_url}">افتح صفحة الإعلان</a>\n\n'
        "1️⃣ افتح الرابط\n"
        "2️⃣ اضغط <b>📺 شاهد الإعلان</b>\n"
        "3️⃣ انتظر 15 ثانية حتى ينتهي العداد\n"
        "4️⃣ ارجع هنا واضغط الزر 👇",
        reply_markup={"inline_keyboard": [[
            {"text": "✅ شاهدت الإعلان — حمّل الآن", "callback_data": "adwatch:done"}
        ]]},
        disable_web_page_preview=True,
    )


def handle_adwatch_done(chat_id: int, cq_id: str):
    data = pending.get(chat_id, {})
    url = data.get("ad_pending_url", "")
    if not url:
        answer_callback(cq_id, "⚠️ انتهت الجلسة")
        send_message(chat_id, "⚠️ انتهت الجلسة، أرسل الرابط مجدداً.")
        return
    if not data.get("ad_verified"):
        answer_callback(cq_id, "❌ افتح رابط الإعلان أولاً!")
        send_message(
            chat_id,
            "⚠️ <b>لم يتم التحقق من مشاهدة الإعلان</b>\n\n"
            "تأكد أنك اتبعت الخطوات:\n"
            "1️⃣ افتح الرابط الذي أرسلناه\n"
            "2️⃣ اضغط زر <b>📺 شاهد الإعلان</b>\n"
            "3️⃣ انتظر حتى ينتهي العداد\n\n"
            "ثم ارجع هنا واضغط الزر مجدداً 👇"
        )
        return
    pending.pop(chat_id, {})
    answer_callback(cq_id, "✅ جاري التحميل...")
    threading.Thread(target=_do_download, args=(chat_id, url), daemon=True).start()


def handle_pre_checkout(query: dict):
    _post("answerPreCheckoutQuery", json={
        "pre_checkout_query_id": query.get("id"),
        "ok": True,
    })


def handle_successful_payment(chat_id: int, payment: dict):
    payload = payment.get("invoice_payload", "")
    parts = payload.split("_")
    if len(parts) < 2:
        return
    try:
        days = int(parts[1])
    except ValueError:
        return
    import datetime
    expires = (datetime.datetime.now() + datetime.timedelta(days=days)).strftime("%Y-%m-%d %H:%M")
    premium_users[chat_id] = expires
    _save_premium()
    stars = payment.get("total_amount", 0)
    send_message(
        chat_id,
        f"🎉 <b>تم الدفع بنجاح!</b>\n\n"
        f"⭐ دفعت: <b>{stars} Stars</b>\n"
        f"💎 الباقة: <b>{days} يوم</b>\n"
        f"📅 تنتهي في: <b>{expires}</b>\n\n"
        "استمتع بتحميلات غير محدودة! 🚀"
    )
    notify_admins(
        f"💰 <b>اشتراك جديد!</b>\n"
        f"👤 Chat ID: <code>{chat_id}</code>\n"
        f"⭐ {stars} Stars — {days} يوم"
    )


def handle_redeem(chat_id: int, code: str):
    if not code:
        send_message(chat_id, "💎 أرسل الكود هكذا:\n/redeem XXXX-XXXXXXXX")
        return
    result = redeem_code(chat_id, code)
    if "error" in result:
        send_message(chat_id, f"❌ {result['error']}")
        return
    days = result.get("days", 30)
    expires = result.get("expires_at", "")
    premium_users[chat_id] = expires
    _save_premium()
    send_message(
        chat_id,
        f"🎉 <b>تم تفعيل البريميوم!</b>\n\n"
        f"💎 مدة الاشتراك: <b>{days} يوم</b>\n"
        f"📅 ينتهي في: <b>{expires}</b>\n\n"
        "استمتع بتحميلات غير محدودة! 🚀"
    )


def handle_status(chat_id: int):
    import datetime
    if chat_id in ADMIN_IDS:
        send_message(chat_id, "👑 <b>أدمن</b> — وصول غير محدود")
        return
    if is_premium(chat_id):
        exp = premium_users.get(chat_id, "")
        if exp == "lifetime":
            send_message(chat_id, "💎 <b>بريميوم مدى الحياة</b> ✅")
        else:
            send_message(chat_id, f"💎 <b>بريميوم نشط</b>\n📅 ينتهي: <b>{exp}</b>")
    else:
        today = datetime.date.today().isoformat()
        data = user_downloads.get(chat_id, {})
        used = data.get("count", 0) if data.get("date") == today else 0
        remaining = max(0, _daily_limit[0] - used)
        send_message(
            chat_id,
            f"🆓 <b>حساب مجاني</b>\n\n"
            f"⬇️ تحميلاتك اليوم: <b>{used}/{_daily_limit[0]}</b>\n"
            f"✅ متبقي: <b>{remaining}</b>\n\n"
            "للترقية: /redeem + كود البريميوم"
        )


def _progress_bar(percent: int) -> str:
    filled = int(percent / 10)
    bar = "▓" * filled + "░" * (10 - filled)
    return f"[{bar}] {percent}%"


def _remaining_text(chat_id: int) -> str:
    """Returns daily counter line for free users, empty for premium/admin."""
    if chat_id in ADMIN_IDS or is_premium(chat_id):
        return ""
    import datetime
    today = datetime.date.today().isoformat()
    data = user_downloads.get(chat_id, {})
    used = data.get("count", 0) if data.get("date") == today else 0
    remaining = max(0, _daily_limit[0] - used)
    return f"\n\n📊 تحميلاتك اليوم: <b>{used}/{_daily_limit[0]}</b> | متبقي: <b>{remaining}</b>"


def _do_download(chat_id: int, url: str, format_id: str = "best[ext=mp4]/best[height<=720]/best", title: str = "فيديو"):
    """Start download without checking the daily limit."""
    if chat_id in active_downloads:
        send_message(chat_id, "⏳ يوجد تحميل جارٍ بالفعل، انتظر حتى ينتهي.")
        return
    active_downloads.add(chat_id)
    try:
        platform = detect_platform(url)
        is_audio = "bestaudio" in format_id
        label = "🎵 الصوت" if is_audio else f"من <b>{platform}</b>"
        send_message(chat_id, f"⬇️ جاري التحميل {label}...")
        result = site_download(url, format_id)
        if "error" in result:
            send_message(chat_id, f"❌ <b>خطأ:</b> {result['error']}")
            return
        task_id = result.get("task_id", "")
        if not task_id:
            send_message(chat_id, "❌ فشل بدء التحميل.")
            return
        _finish_download(chat_id, task_id, url, title)
    finally:
        active_downloads.discard(chat_id)


def _handle_multiple_urls(chat_id: int, urls: list, first_name: str):
    """Download multiple URLs sequentially."""
    for i, url in enumerate(urls[:3]):
        if not check_download_limit(chat_id):
            send_message(chat_id,
                f"⛔ نُفّذت <b>{i}</b> من <b>{len(urls)}</b> تحميلات — وصلت للحد اليومي" if i > 0
                else f"⛔ وصلت للحد اليومي المجاني ({_daily_limit[0]} تحميلات)")
            return
        while chat_id in active_downloads:
            time.sleep(1)
        _do_download(chat_id, url)
        while chat_id in active_downloads:
            time.sleep(1)


def _fmt_duration(seconds) -> str:
    if not seconds:
        return ""
    s = int(seconds)
    h, remainder = divmod(s, 3600)
    m, sec = divmod(remainder, 60)
    if h:
        return f"⏱️ {h}:{m:02d}:{sec:02d}"
    return f"⏱️ {m}:{sec:02d}"


def handle_url(chat_id: int, url: str, first_name: str):
    if chat_id in active_downloads:
        send_message(chat_id, "⏳ يوجد تحميل جارٍ بالفعل، انتظر حتى ينتهي.")
        return

    # البريميوم معفى (دفع مسبق) — الباقي يجب المرور بالتطبيق
    if not is_premium(chat_id) and not _session_has(chat_id):
        send_message(
            chat_id,
            "⛔ <b>يجب فتح التطبيق أولاً لتحميل الفيديو</b>\n\n"
            "١. افتح التطبيق\n"
            "٢. الصق رابط الفيديو\n"
            "٣. اضغط «فتح البوت» — سيبدأ التحميل تلقائياً 🚀",
            reply_markup={"inline_keyboard": [[
                {"text": "📲 فتح التطبيق", "url": "https://play.google.com/store/apps/details?id=com.nazzilhaplus.app"}
            ]]},
        )
        return
    if not is_premium(chat_id):
        _session_remove(chat_id)  # استهلاك الجلسة للمستخدمين المجانيين فقط
    platform = detect_platform(url)
    pending[chat_id] = {"fmt_url": url, "title": "فيديو"}
    rem = _remaining_text(chat_id)
    send_message(
        chat_id,
        f"🎬 <b>{platform}</b> — اختر الصيغة:{rem}",
        reply_markup={"inline_keyboard": [[
            {"text": "🎬 فيديو", "callback_data": "fmt:video"},
            {"text": "🎵 MP3", "callback_data": "fmt:audio"},
        ]]}
    )


def _finish_download(chat_id: int, task_id: str, url: str, title: str):
    deadline = time.time() + 300
    last_percent = -1
    progress_msg_id = None

    while time.time() < deadline:
        prog = site_progress(task_id)
        status = prog.get("status", "")
        percent = prog.get("percent", 0)

        if status == "downloading" and abs(percent - last_percent) >= 15:
            last_percent = percent
            bar_text = f"⬇️ جاري التحميل...\n{_progress_bar(percent)}"
            if progress_msg_id:
                _post("editMessageText", json={"chat_id": chat_id, "message_id": progress_msg_id, "text": bar_text})
            else:
                res = send_message(chat_id, bar_text)
                progress_msg_id = (res.get("result") or {}).get("message_id")

        if status == "done":
            file_name = prog.get("file", "")
            display_name = prog.get("filename", title)
            file_path = DOWNLOAD_DIR / file_name

            if not file_path.exists():
                send_message(chat_id, "❌ الملف غير موجود على السيرفر.")
                return

            ext = file_path.suffix.lower()
            caption_text = f"✅ <b>{display_name[:80]}</b>\n\n🤖 @nazzilhaplus_bot | 🌐 {SITE_URL}"

            file_size_mb = file_path.stat().st_size / (1024 * 1024)

            try:
                if ext in (".mp3", ".m4a", ".ogg", ".wav"):
                    send_audio(chat_id, str(file_path), caption_text)
                elif file_size_mb <= 50:
                    send_video(chat_id, str(file_path), caption_text)
                else:
                    send_document(chat_id, str(file_path), caption_text)
            except Exception as e:
                log.error("Failed to send file: %s", e)
                send_message(chat_id, f"⚠️ تعذّر إرسال الملف مباشرةً.\n\n📥 حمّله من الموقع:\n{SITE_URL}")

            return_btn = {"inline_keyboard": [[
                {"text": "↩️ العودة للتطبيق", "url": SITE_URL}
            ]]}
            send_message(chat_id, "✅ اكتمل التحميل!\n\nارجع للتطبيق لتحميل المزيد 👇", reply_markup=return_btn)

            _add_to_history(chat_id, url, display_name, detect_platform(url))
            notify_admin_download(url, display_name, chat_id)
            return

        elif status == "error":
            err = prog.get("error", "خطأ غير معروف")
            retry_btn = {"inline_keyboard": [[
                {"text": "🔄 حاول مرة أخرى", "url": SITE_URL},
            ]]}
            send_message(chat_id, f"❌ <b>فشل التحميل:</b>\n{err}", reply_markup=retry_btn)
            return

        time.sleep(2)

    retry_btn = {"inline_keyboard": [[{"text": "🔄 حاول من الموقع", "url": SITE_URL}]]}
    send_message(chat_id, "⏰ انتهت مهلة التحميل — حاول مرة أخرى.", reply_markup=retry_btn)


def handle_format_choice(chat_id: int, callback_query_id: str, format_id: str):
    answer_callback(callback_query_id, "⏳ جاري التحميل...")

    data = pending.get(chat_id)
    if not data:
        send_message(chat_id, "⚠️ انتهت الجلسة، أعد إرسال الرابط.")
        return

    url = data["url"]
    cache_id = data.get("cache_id", "")
    title = data.get("title", "فيديو")
    pending.pop(chat_id, None)

    send_message(chat_id, "⬇️ جاري التحميل، انتظر قليلاً...")

    result = site_download(url, format_id, cache_id)
    if "error" in result:
        send_message(chat_id, f"❌ <b>خطأ:</b> {result['error']}")
        return

    task_id = result.get("task_id", "")
    if not task_id:
        send_message(chat_id, "❌ فشل بدء التحميل.")
        return

    _finish_download(chat_id, task_id, url, title)


def handle_history(chat_id: int):
    hist = user_history.get(chat_id, [])
    if not hist:
        send_message(chat_id, "📭 لا يوجد سجل تحميلات بعد.\n\nأرسل رابط فيديو لتحميله!")
        return
    keyboard = [
        [{"text": f"🔁 {item['title'][:35]}", "callback_data": f"hist:{i}"}]
        for i, item in enumerate(hist)
    ]
    keyboard.append([{"text": "🗑️ مسح السجل", "callback_data": "hist:clear"}])
    send_message(
        chat_id,
        f"📋 <b>آخر تحميلاتك ({len(hist)}):</b>",
        reply_markup={"inline_keyboard": keyboard},
    )


# ── Admin notifications ────────────────────────────────────────────────────────

def _notify(text: str) -> None:
    """Send to admin channel if configured, else fall back to each admin ID."""
    if ADMIN_CHANNEL_ID:
        send_message(ADMIN_CHANNEL_ID, text)
    else:
        for admin_id in ADMIN_IDS:
            send_message(admin_id, text)


def notify_admin_download(url: str, title: str, user_chat_id: int):
    if not ADMIN_CHANNEL_ID and not ADMIN_IDS:
        return
    platform = detect_platform(url)
    user_info = known_users.get(user_chat_id, {})
    user_name = user_info.get("name", "مجهول") if isinstance(user_info, dict) else str(user_info)
    user_username = user_info.get("username") if isinstance(user_info, dict) else None
    username_line = f"🔖 المعرف: @{user_username}\n" if user_username else ""
    text = (
        "📥 <b>تحميل جديد عبر البوت</b>\n\n"
        f"👤 الاسم: <b>{user_name}</b>\n"
        f"{username_line}"
        f"🆔 Chat ID: <code>{user_chat_id}</code>\n"
        f"📱 المنصة: {platform}\n"
        f"🎬 العنوان: {title[:80]}\n"
        f"🔗 الرابط: {url[:100]}"
    )
    _notify(text)


def notify_admins(text: str):
    _notify(text)


def _daily_report():
    import datetime
    while True:
        now = datetime.datetime.now()
        next_midnight = (now + datetime.timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
        time.sleep((next_midnight - now).total_seconds())
        try:
            now = datetime.datetime.now()
            data = site_stats()
            users_count = len(known_users)
            blocked_count = len(blocked_users)
            if "error" not in data:
                platforms = data.get("platform_counts", {})
                platform_lines = "\n".join(f"  • {k}: {v:,}" for k, v in platforms.items() if v > 0) or "  لا بيانات"
                report_text = (
                    "📅 <b>التقرير اليومي</b>\n\n"
                    f"📆 {now.strftime('%Y-%m-%d')}\n"
                    f"⬇️ إجمالي التحميلات: <b>{data.get('total_downloads', 0):,}</b>\n"
                    f"📅 تحميلات اليوم: <b>{data.get('today_downloads', 0):,}</b>\n"
                    f"❌ فاشلة: <b>{data.get('failed_downloads', 0):,}</b>\n"
                    f"👥 مستخدمو البوت: <b>{users_count}</b> | محظور: <b>{blocked_count}</b>\n\n"
                    f"📱 المنصات:\n{platform_lines}"
                )
            else:
                report_text = "📅 <b>التقرير اليومي</b>\n\n⚠️ تعذّر جلب البيانات."
            notify_admins(report_text)
        except Exception as e:
            log.error("Daily report error: %s", e)


# ── Routing ────────────────────────────────────────────────────────────────────

def handle_message(msg: dict):
    chat = msg.get("chat", {})
    chat_id: int = chat.get("id", 0)
    text: str = (msg.get("text") or "").strip()
    first_name: str = msg.get("from", {}).get("first_name", "مستخدم")

    if not text:
        return

    username: str | None = msg.get("from", {}).get("username")
    known_users[chat_id] = {"name": first_name, "username": username}

    if chat_id in blocked_users:
        send_message(chat_id, "⛔ عذراً، تم تعليق حسابك. تواصل مع الدعم.")
        return

    # admin waiting-state handlers
    if chat_id in ADMIN_IDS:
        state = pending.get(chat_id, {})

        if state.get("waiting_block"):
            pending.pop(chat_id, None)
            if text.lstrip("-").isdigit():
                target_id = int(text)
                blocked_users.add(target_id)
                _save_blocked()
                send_message(chat_id, f"✅ تم حظر المستخدم <code>{target_id}</code>.", reply_markup=_build_admin_keyboard())
            else:
                send_message(chat_id, "❌ أرسل Chat ID رقمياً فقط.")
            return

        if state.get("waiting_unblock"):
            pending.pop(chat_id, None)
            if text.lstrip("-").isdigit():
                target_id = int(text)
                blocked_users.discard(target_id)
                _save_blocked()
                send_message(chat_id, f"✅ تم رفع الحظر عن <code>{target_id}</code>.", reply_markup=_build_admin_keyboard())
            else:
                send_message(chat_id, "❌ أرسل Chat ID رقمياً فقط.")
            return

        if state.get("waiting_welcome"):
            pending.pop(chat_id, None)
            if text.strip() == "/reset":
                custom_welcome.clear()
                _save_welcome()
                send_message(chat_id, "✅ أُعيدت رسالة الترحيب إلى الافتراضية.", reply_markup=_build_admin_keyboard())
            else:
                custom_welcome.clear()
                custom_welcome.append(text)
                _save_welcome()
                send_message(chat_id, "✅ تم حفظ رسالة الترحيب الجديدة!", reply_markup=_build_admin_keyboard())
            return

        if state.get("waiting_setlimit"):
            pending.pop(chat_id, None)
            if text.isdigit() and 1 <= int(text) <= 50:
                _daily_limit[0] = int(text)
                _save_config()
                send_message(chat_id, f"✅ تم تغيير الحد اليومي إلى <b>{_daily_limit[0]}</b> تحميلات.", reply_markup=_build_admin_keyboard())
            else:
                send_message(chat_id, "❌ أرسل رقماً بين 1 و 50.")
            return

    # broadcast handler
    if pending.get(chat_id, {}).get("waiting_broadcast") and chat_id in ADMIN_IDS:
        pending.pop(chat_id, None)
        sent = 0
        failed = 0
        for uid in list(known_users.keys()):
            if uid == chat_id:
                continue
            res = send_message(uid, f"📢 <b>رسالة من الإدارة:</b>\n\n{text}")
            if res.get("ok"):
                sent += 1
            else:
                failed += 1
        send_message(chat_id, f"✅ أُرسلت لـ <b>{sent}</b> مستخدم\n❌ فشلت: <b>{failed}</b>", reply_markup=_build_admin_keyboard())
        return

    if text.startswith("/start"):
        parts = text.split(maxsplit=1)
        param = parts[1].strip() if len(parts) > 1 else ""
        handle_start(chat_id, first_name, param)
    elif text.startswith("/help") or text == "ℹ️ المساعدة":
        handle_help(chat_id)
    elif text.startswith("/stats") or text == "📊 الإحصائيات":
        handle_stats(chat_id)
    elif text.startswith("/site") or text == "🌐 الموقع":
        handle_site(chat_id)
    elif text.startswith("/platforms"):
        handle_platforms(chat_id)
    elif text.startswith("/share") or text == "📣 شارك البوت":
        handle_share(chat_id)
    elif text.startswith("/redeem"):
        parts = text.split(maxsplit=1)
        handle_redeem(chat_id, parts[1].strip() if len(parts) > 1 else "")
    elif text.startswith("/status"):
        handle_status(chat_id)
    elif text.startswith("/history"):
        handle_history(chat_id)
    elif text.startswith("/subscribe") or text == "💎 بريميوم":
        handle_subscribe_menu(chat_id)
    elif text == "🔥 الأكثر تحميلاً":
        handle_top(chat_id)
    elif text == "📲 حمّل فيديو":
        handle_download_menu(chat_id)
    elif (text.startswith("/admin") or text == "🛠️ لوحة التحكم") and chat_id in ADMIN_IDS:
        handle_admin_panel(chat_id)
    else:
        # إذا كان المستخدم في وضع انتظار رابط بعد اختيار منصة
        waiting = pending.get(chat_id, {}).get("waiting_url")
        urls = URL_PATTERN.findall(text)
        if urls:
            pending.pop(chat_id, None)
            if len(urls) == 1:
                threading.Thread(target=handle_url, args=(chat_id, urls[0], first_name), daemon=True).start()
            else:
                send_message(chat_id, f"🔗 وجدت <b>{len(urls[:3])}</b> روابط — سأحمّلها بالترتيب...")
                threading.Thread(target=_handle_multiple_urls, args=(chat_id, urls, first_name), daemon=True).start()
        elif waiting:
            send_message(chat_id, "❗ هذا ليس رابطاً صحيحاً، أرسل رابط الفيديو مباشرةً.")
        else:
            send_message(chat_id, "❓ أرسل رابط فيديو لتحميله، أو اضغط 📲 حمّل فيديو.")


def handle_callback_query(cq: dict):
    cq_id: str = cq.get("id", "")
    chat_id: int = cq.get("message", {}).get("chat", {}).get("id", 0)
    data: str = cq.get("data", "")

    if data.startswith("dl:"):
        format_id = data[3:]
        threading.Thread(
            target=handle_format_choice,
            args=(chat_id, cq_id, format_id),
            daemon=True,
        ).start()
    elif data.startswith("platform:"):
        platform = data[9:]
        handle_platform_selected(chat_id, cq_id, platform)
    elif data.startswith("adm:") and chat_id in ADMIN_IDS:
        action = data[4:]
        threading.Thread(target=handle_admin_callback, args=(chat_id, cq_id, action), daemon=True).start()
    elif data.startswith("sub:"):
        plan = data[4:]
        threading.Thread(target=handle_subscribe_callback, args=(chat_id, cq_id, plan), daemon=True).start()
    elif data == "adwatch:start":
        threading.Thread(target=handle_adwatch_start, args=(chat_id, cq_id), daemon=True).start()
    elif data == "adwatch:done":
        threading.Thread(target=handle_adwatch_done, args=(chat_id, cq_id), daemon=True).start()
    elif data.startswith("fmt:"):
        fmt = data[4:]
        pdata = pending.get(chat_id, {})
        url = pdata.get("fmt_url", "")
        title = pdata.get("title", "فيديو")
        if not url:
            answer_callback(cq_id, "⚠️ انتهت الجلسة، أرسل الرابط مجدداً")
            return
        pending.pop(chat_id, None)
        answer_callback(cq_id, "⏳ جاري التحميل...")
        format_id = "bestaudio/best" if fmt == "audio" else "best[ext=mp4]/best[height<=720]/best"
        threading.Thread(target=_do_download, args=(chat_id, url, format_id, title), daemon=True).start()
    elif data.startswith("hist:"):
        val = data[5:]
        if val == "clear":
            answer_callback(cq_id, "✅ تم مسح السجل")
            user_history.pop(chat_id, None)
            _save_history()
            send_message(chat_id, "✅ تم مسح سجل التحميلات.")
        else:
            try:
                idx = int(val)
                hist = user_history.get(chat_id, [])
                item = hist[idx]
                answer_callback(cq_id, "⏳ جاري التحضير...")
                if not check_download_limit(chat_id):
                    pending[chat_id] = {"ad_pending_url": item["url"]}
                    send_message(chat_id,
                        f"⛔ <b>وصلت للحد اليومي المجاني ({_daily_limit[0]} تحميلات)</b>\n\nاختر طريقة للمتابعة:",
                        reply_markup={"inline_keyboard": [
                            [{"text": "📺 شاهد إعلان وحمّل مجاناً", "callback_data": "adwatch:start"}],
                            [{"text": "💎 اشترك بالبريميوم", "callback_data": "sub:menu"}],
                        ]})
                else:
                    pending[chat_id] = {"fmt_url": item["url"], "title": item["title"]}
                    rem = _remaining_text(chat_id)
                    send_message(chat_id,
                        f"🎬 <b>{item['title'][:80]}</b>\n📱 {item['platform']}\n\nاختر الصيغة:{rem}",
                        reply_markup={"inline_keyboard": [[
                            {"text": "🎬 فيديو", "callback_data": "fmt:video"},
                            {"text": "🎵 MP3", "callback_data": "fmt:audio"},
                        ]]})
            except (ValueError, IndexError):
                answer_callback(cq_id, "⚠️ انتهت الجلسة")
    else:
        answer_callback(cq_id)


def handle_inline_query(query: dict):
    query_id = query.get("id", "")
    text = (query.get("query") or "").strip()
    urls = URL_PATTERN.findall(text)

    if not urls:
        _post("answerInlineQuery", json={
            "inline_query_id": query_id,
            "results": [{
                "type": "article",
                "id": "help",
                "title": "📥 الصق رابط الفيديو بعد اسم البوت",
                "description": "مثال: @nazzilhaplus_bot https://vm.tiktok.com/xxx",
                "input_message_content": {
                    "message_text": "📥 لتحميل فيديو أرسل الرابط لـ @nazzilhaplus_bot",
                    "parse_mode": "HTML",
                },
            }],
            "cache_time": 0,
            "switch_pm_text": "📥 افتح البوت وأرسل الرابط",
            "switch_pm_parameter": "inline",
        })
        return

    url = urls[0]
    platform = detect_platform(url)
    results = [{
        "type": "article",
        "id": "dl1",
        "title": f"📥 تحميل من {platform}",
        "description": "اضغط لمشاركة رابط التحميل في المحادثة",
        "input_message_content": {
            "message_text": (
                f"📥 <b>نزّل هذا الفيديو من {platform}</b>\n\n"
                f"🔗 {url}\n\n"
                "👇 اضغط الزر للتحميل"
            ),
            "parse_mode": "HTML",
        },
        "reply_markup": {"inline_keyboard": [[
            {"text": "📥 تحميل في البوت", "url": "https://t.me/nazzilhaplus_bot"}
        ]]},
    }]
    _post("answerInlineQuery", json={"inline_query_id": query_id, "results": results, "cache_time": 0})


def _premium_expiry_notifier():
    import datetime
    while True:
        time.sleep(3600)
        try:
            now = datetime.datetime.now()
            for uid, exp in list(premium_users.items()):
                if exp == "lifetime":
                    continue
                try:
                    exp_dt = datetime.datetime.strptime(exp, "%Y-%m-%d %H:%M")
                    diff = exp_dt - now
                    if 0 <= diff.days <= 2 and diff.seconds < 3600:
                        send_message(int(uid),
                            f"⚠️ <b>تنبيه: بريميومك ينتهي بعد {diff.days} يوم!</b>\n\n"
                            f"📅 تاريخ الانتهاء: <b>{exp}</b>\n\n"
                            "جدّد الآن للاستمرار في التحميل غير المحدود 👇",
                            reply_markup=SUBSCRIBE_KEYBOARD)
                except Exception:
                    pass
        except Exception as e:
            log.error("Premium expiry notifier: %s", e)


def process_update(update: dict):
    try:
        if "message" in update:
            msg = update["message"]
            if "successful_payment" in msg:
                chat_id = msg.get("chat", {}).get("id", 0)
                handle_successful_payment(chat_id, msg["successful_payment"])
            else:
                handle_message(msg)
        elif "callback_query" in update:
            handle_callback_query(update["callback_query"])
        elif "pre_checkout_query" in update:
            handle_pre_checkout(update["pre_checkout_query"])
        elif "inline_query" in update:
            handle_inline_query(update["inline_query"])
    except Exception as e:
        log.exception("Error in process_update: %s", e)


# ── Webhook setup ──────────────────────────────────────────────────────────────

def setup_webhook():
    if not BOT_TOKEN:
        log.warning("TELEGRAM_BOT_TOKEN غير مضبوط")
        return
    webhook_url = f"{SITE_URL}/webhook/telegram/{BOT_TOKEN}"
    set_webhook(webhook_url)
    threading.Thread(target=_daily_report, daemon=True, name="daily-report").start()
    threading.Thread(target=_premium_expiry_notifier, daemon=True, name="premium-notifier").start()
    if ADMIN_CHANNEL_ID or ADMIN_IDS:
        notify_admins("🟢 <b>البوت شغّال!</b>\nVIP-DL Bot انطلق بنجاح.")
