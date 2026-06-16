"""
VIP-DL Telegram Bot — Webhook mode
يدعم: تحميل فيديوهات، إشعارات الأدمن، إحصائيات الموقع
"""
import os
import re
import time
import logging
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

API = f"https://api.telegram.org/bot{BOT_TOKEN}"
DOWNLOAD_DIR = Path("downloads")
DOWNLOAD_DIR.mkdir(exist_ok=True)

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


# ── User state ─────────────────────────────────────────────────────────────────
pending: dict[int, dict] = {}


# ── Message handlers ───────────────────────────────────────────────────────────

HELP_TEXT = """🤖 <b>بوت VIP-DL للتحميل</b>

أرسل لي رابط الفيديو مباشرةً وسأحمله لك!

<b>المنصات المدعومة:</b>
• TikTok  |  Instagram  |  Facebook
• Twitter/X  |  YouTube  |  Pinterest  |  وأكثر

<b>الأوامر:</b>
/start — رسالة الترحيب
/help — هذه القائمة
/platforms — المنصات المدعومة
/stats — إحصائيات الموقع
/site — رابط الموقع

📎 فقط الصق الرابط وأنا أتولى الباقي!"""


def handle_start(chat_id: int, first_name: str):
    text = (
        f"أهلاً {first_name}! 👋\n\n"
        "أنا بوت <b>VIP-DL</b> لتحميل الفيديوهات.\n"
        "أرسل لي أي رابط فيديو وسأحمله لك مجاناً!\n\n"
        "اكتب /help لعرض المساعدة."
    )
    send_message(chat_id, text)


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
        "▶️ YouTube\n"
        "📌 Pinterest\n"
        "➕ والمئات من المواقع الأخرى!\n\n"
        "فقط أرسل الرابط وأنا أتولى الباقي 😉"
    )
    send_message(chat_id, text)


def handle_url(chat_id: int, url: str, first_name: str):
    platform = detect_platform(url)
    send_message(chat_id, f"🔍 جاري تحليل الرابط من <b>{platform}</b>...")

    info = site_info(url)
    if "error" in info:
        send_message(chat_id, f"❌ <b>خطأ:</b> {info['error']}")
        return

    title = info.get("title", "فيديو")
    formats: list = info.get("formats", [])
    cache_id = info.get("cache_id", "")

    if not formats:
        send_message(chat_id, "⚠️ لم يتم العثور على صيغ متاحة للتحميل.")
        return

    pending[chat_id] = {"url": url, "formats": formats, "cache_id": cache_id, "title": title}

    buttons = []
    for fmt in formats[:8]:
        label = fmt.get("label", "")
        fmt_id = fmt.get("format_id", "")
        size_str = ""
        size = fmt.get("filesize")
        if size:
            size_mb = size / (1024 * 1024)
            size_str = f" ({size_mb:.1f} MB)"

        btn_text = f"{'🎵' if fmt.get('type') == 'audio' else '🎬'} {label}{size_str}"
        buttons.append([{"text": btn_text, "callback_data": f"dl:{fmt_id}"}])

    reply_markup = {"inline_keyboard": buttons}
    duration = info.get("duration")
    duration_str = f" | ⏱ {duration // 60}:{duration % 60:02d}" if duration else ""
    caption = f"🎬 <b>{title[:100]}</b>{duration_str}\n\nاختر الجودة:"

    _post("sendMessage", json={
        "chat_id": chat_id,
        "text": caption,
        "parse_mode": "HTML",
        "reply_markup": reply_markup,
    })


def handle_format_choice(chat_id: int, callback_query_id: str, format_id: str):
    answer_callback(callback_query_id, "⏳ جاري التحميل...")

    data = pending.get(chat_id)
    if not data:
        send_message(chat_id, "⚠️ انتهت الجلسة، أعد إرسال الرابط.")
        return

    url = data["url"]
    cache_id = data.get("cache_id", "")
    title = data.get("title", "فيديو")

    send_message(chat_id, "⬇️ جاري التحميل، انتظر قليلاً...")

    result = site_download(url, format_id, cache_id)
    if "error" in result:
        send_message(chat_id, f"❌ <b>خطأ:</b> {result['error']}")
        return

    task_id = result.get("task_id", "")
    if not task_id:
        send_message(chat_id, "❌ فشل بدء التحميل.")
        return

    deadline = time.time() + 300
    last_percent = -1

    while time.time() < deadline:
        prog = site_progress(task_id)
        status = prog.get("status", "")
        percent = prog.get("percent", 0)

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

            pending.pop(chat_id, None)
            notify_admin_download(url, title, chat_id)
            return

        elif status == "error":
            err = prog.get("error", "خطأ غير معروف")
            send_message(chat_id, f"❌ <b>فشل التحميل:</b>\n{err}")
            pending.pop(chat_id, None)
            return

        elif status in ("downloading", "processing"):
            last_percent = percent

        time.sleep(3)

    send_message(chat_id, "⏰ انتهت مهلة التحميل. الفيديو كبير جداً أو الخادم مشغول — حاول مرة أخرى.")


# ── Admin notifications ────────────────────────────────────────────────────────

def notify_admin_download(url: str, title: str, user_chat_id: int):
    if not ADMIN_IDS:
        return
    platform = detect_platform(url)
    text = (
        "📥 <b>تحميل جديد عبر البوت</b>\n\n"
        f"👤 Chat ID: <code>{user_chat_id}</code>\n"
        f"📱 المنصة: {platform}\n"
        f"🎬 العنوان: {title[:80]}\n"
        f"🔗 الرابط: {url[:100]}"
    )
    for admin_id in ADMIN_IDS:
        send_message(admin_id, text)


def notify_admins(text: str):
    for admin_id in ADMIN_IDS:
        send_message(admin_id, text)


# ── Routing ────────────────────────────────────────────────────────────────────

def handle_message(msg: dict):
    chat = msg.get("chat", {})
    chat_id: int = chat.get("id", 0)
    text: str = (msg.get("text") or "").strip()
    first_name: str = msg.get("from", {}).get("first_name", "مستخدم")

    if not text:
        return

    if text.startswith("/start"):
        handle_start(chat_id, first_name)
    elif text.startswith("/help"):
        handle_help(chat_id)
    elif text.startswith("/stats"):
        handle_stats(chat_id)
    elif text.startswith("/site"):
        handle_site(chat_id)
    elif text.startswith("/platforms"):
        handle_platforms(chat_id)
    else:
        urls = URL_PATTERN.findall(text)
        if urls:
            threading.Thread(
                target=handle_url,
                args=(chat_id, urls[0], first_name),
                daemon=True,
            ).start()
        else:
            send_message(
                chat_id,
                "❓ أرسل رابط فيديو لتحميله، أو اكتب /help للمساعدة."
            )


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
    else:
        answer_callback(cq_id)


def process_update(update: dict):
    if "message" in update:
        handle_message(update["message"])
    elif "callback_query" in update:
        handle_callback_query(update["callback_query"])


# ── Webhook setup ──────────────────────────────────────────────────────────────

def setup_webhook():
    if not BOT_TOKEN:
        log.warning("TELEGRAM_BOT_TOKEN غير مضبوط")
        return
    webhook_url = f"{SITE_URL}/webhook/telegram/{BOT_TOKEN}"
    set_webhook(webhook_url)
    if ADMIN_IDS:
        notify_admins("🟢 <b>البوت شغّال!</b>\nVIP-DL Bot انطلق بنجاح.")
