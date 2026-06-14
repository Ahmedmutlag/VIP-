import os
import re
import uuid
import threading
import time
import subprocess
import functools
import json
import smtplib
import secrets
import urllib.request
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart
from pathlib import Path
from datetime import datetime, timedelta, timezone

TZ_OFFSET = timedelta(hours=3)  # Arabia Standard Time (UTC+3)

def now():
    return datetime.now(timezone.utc).astimezone(timezone(TZ_OFFSET)).replace(tzinfo=None)
from flask import Flask, request, jsonify, send_file, render_template, Response, session, redirect, url_for
from flask_cors import CORS
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address
from flask_compress import Compress
from werkzeug.middleware.proxy_fix import ProxyFix
from werkzeug.security import generate_password_hash, check_password_hash
import yt_dlp


YTDLP_CONSTRAINT = "yt-dlp<2026.06.01"  # pin to May 2026 — June 9 broke TikTok/Facebook

def auto_update_ytdlp():
    """Install/downgrade yt-dlp to the pinned constraint."""
    try:
        subprocess.run(
            ["pip", "install", YTDLP_CONSTRAINT, "--quiet", "--break-system-packages"],
            timeout=120, check=False
        )
        stats["ytdlp_updated"] = now().strftime("%Y-%m-%d %H:%M")
    except Exception:
        pass

# Run on startup to force the pinned version even if Render cached a newer one
threading.Thread(target=auto_update_ytdlp, daemon=True).start()

_server_start = time.time()
_last_activity = time.time()

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY", "vip-secret-2026-xk9z")
app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1, x_host=1)
CORS(app, origins=["https://www.vip-dl.com", "https://vip-dl.com"])
Compress(app)

@app.before_request
def track_activity():
    global _last_activity
    _last_activity = time.time()


@app.after_request
def add_cache_headers(response):
    path = request.path
    if path.startswith('/static/'):
        response.cache_control.max_age = 604800  # 7 days
        response.cache_control.public = True
    elif path in ('/', '/api/public-stats'):
        response.cache_control.max_age = 60
        response.cache_control.public = True

    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['X-Frame-Options'] = 'SAMEORIGIN'
    response.headers['Referrer-Policy'] = 'strict-origin-when-cross-origin'
    response.headers['Permissions-Policy'] = 'camera=(), microphone=(), geolocation=()'
    response.headers['Content-Security-Policy'] = (
        "default-src 'self'; "
        "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://cdnjs.cloudflare.com "
        "https://pagead2.googlesyndication.com https://www.googletagmanager.com; "
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
        "font-src 'self' https://fonts.gstatic.com; "
        "img-src 'self' data: https:; "
        "connect-src 'self' https://www.google-analytics.com https://analytics.google.com https://stats.g.doubleclick.net https://region1.google-analytics.com; "
        "frame-src https://googleads.g.doubleclick.net https://www.google.com;"
    )
    if request.is_secure:
        response.headers['Strict-Transport-Security'] = 'max-age=31536000; includeSubDomains'
    return response

limiter = Limiter(
    get_remote_address,
    app=app,
    default_limits=[],
    storage_uri="memory://",
)

# ===== Brute Force Protection =====
login_attempts = {}  # ip -> {"count": int, "locked_until": datetime | None}

def is_locked(ip):
    entry = login_attempts.get(ip)
    if not entry:
        return False, 0
    locked_until = entry.get("locked_until")
    if locked_until and now() < locked_until:
        mins = max(1, int((locked_until - now()).total_seconds() // 60) + 1)
        return True, mins
    return False, 0

def record_failed_login(ip):
    if ip not in login_attempts:
        login_attempts[ip] = {"count": 0, "locked_until": None}
    login_attempts[ip]["count"] += 1
    if login_attempts[ip]["count"] >= 5:
        login_attempts[ip]["locked_until"] = now() + timedelta(minutes=15)
        login_attempts[ip]["count"] = 0

def clear_login_attempts(ip):
    login_attempts.pop(ip, None)

DOWNLOAD_DIR = Path("downloads")
DOWNLOAD_DIR.mkdir(exist_ok=True)

progress_store = {}
info_cache = {}  # cache_id -> {info, expires}

def _cleanup_info_cache():
    while True:
        time.sleep(120)
        now = time.time()
        expired = [k for k, v in list(info_cache.items()) if v["expires"] < now]
        for k in expired:
            info_cache.pop(k, None)

threading.Thread(target=_cleanup_info_cache, daemon=True).start()

STRIPE_PAYMENT_LINK = os.environ.get("STRIPE_PAYMENT_LINK", "#pricing")
ADMIN_USER = os.environ.get("ADMIN_USER", "admin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "vip2026")
ADMIN_EMAIL = os.environ.get("ADMIN_EMAIL", "ahmed.alabdan2@gmail.com")
SMTP_USER = os.environ.get("SMTP_USER", "")
SMTP_PASS = os.environ.get("SMTP_PASS", "")
RESET_SECRET = os.environ.get("RESET_SECRET", "")
INSTAGRAM_COOKIES = os.environ.get("INSTAGRAM_COOKIES", "")  # Netscape cookies.txt content
FACEBOOK_COOKIES  = os.environ.get("FACEBOOK_COOKIES",  "")  # Netscape cookies.txt content
TIKTOK_COOKIES    = os.environ.get("TIKTOK_COOKIES",    "")  # Netscape cookies.txt content

reset_tokens = {}  # token -> {"expires": datetime}

CONFIG_FILE  = Path("data/config.json")
CODES_FILE        = Path("data/codes.json")
RATINGS_FILE      = Path("data/ratings.json")
STATS_FILE        = Path("data/stats.json")
VISITORS_FILE     = Path("data/visitors.json")
DOWNLOAD_LOG_FILE = Path("data/download_log.json")
APP_INSTALLS_FILE = Path("data/app_installs.json")
HOURLY_STATS_FILE = Path("data/hourly_stats.json")
DAILY_STATS_FILE  = Path("data/daily_stats.json")
SETTINGS_FILE     = Path("data/settings.json")
CONFIG_FILE.parent.mkdir(exist_ok=True)

DEFAULT_SETTINGS = {
    "ad_wait_seconds": 10,
    "maintenance_mode": False,
    "ratings_enabled": True,
    "welcome_message": "",
    "app_update_banner": False,
}

def load_visitors():
    if VISITORS_FILE.exists():
        try: return json.loads(VISITORS_FILE.read_text())
        except: pass
    return {}

def save_visitors(data):
    cutoff = (now() - timedelta(days=90)).date().isoformat()
    data = {k: v for k, v in data.items() if k >= cutoff}
    VISITORS_FILE.write_text(json.dumps(data))

def load_settings():
    if SETTINGS_FILE.exists():
        try:
            s = json.loads(SETTINGS_FILE.read_text())
            return {**DEFAULT_SETTINGS, **s}
        except: pass
    return dict(DEFAULT_SETTINGS)

def save_settings(data):
    SETTINGS_FILE.write_text(json.dumps(data, ensure_ascii=False))

def load_download_log():
    if DOWNLOAD_LOG_FILE.exists():
        try: return json.loads(DOWNLOAD_LOG_FILE.read_text())
        except: pass
    return []

def save_download_log(data):
    DOWNLOAD_LOG_FILE.write_text(json.dumps(data, ensure_ascii=False))

def load_hourly_stats():
    if HOURLY_STATS_FILE.exists():
        try: return json.loads(HOURLY_STATS_FILE.read_text())
        except: pass
    return {str(h): 0 for h in range(24)}

def save_hourly_stats(data):
    HOURLY_STATS_FILE.write_text(json.dumps(data))

def load_daily_stats():
    if DAILY_STATS_FILE.exists():
        try: return json.loads(DAILY_STATS_FILE.read_text())
        except: pass
    return {}

def save_daily_stats(data):
    cutoff = (now() - timedelta(days=60)).date().isoformat()
    data = {k: v for k, v in data.items() if k >= cutoff}
    DAILY_STATS_FILE.write_text(json.dumps(data))

BOT_KEYWORDS = [
    "bot", "crawl", "spider", "slurp", "curl", "wget", "python-requests",
    "scrapy", "facebookexternalhit", "twitterbot", "linkedinbot", "whatsapp",
    "googlebot", "bingbot", "yandex", "baidu", "duckduck", "semrush",
    "ahrefsbot", "mj12bot", "dotbot", "petalbot"
]

def is_bot(ua_string):
    ua = (ua_string or "").lower()
    return any(k in ua for k in BOT_KEYWORDS)

def detect_device(ua_string):
    ua = (ua_string or "").lower()
    # Tablet check before mobile (iPad has "mobile" in some UAs)
    if any(x in ua for x in ["ipad", "tablet", "kindle", "playbook"]):
        return "mobile"
    # Mobile check: Android phones, iPhones, WebView
    if any(x in ua for x in ["android", "iphone", "ipod", "mobile", "webview", "wv)"]):
        return "mobile"
    return "desktop"

def record_visit(ip, user_agent=""):
    import hashlib
    if is_bot(user_agent):
        return
    today = now().date().isoformat()
    ip_hash = hashlib.sha256(ip.encode()).hexdigest()[:16]
    device = detect_device(user_agent)
    visitors = load_visitors()
    is_new = not any(
        ip_hash in v.get("ips", [])
        for d, v in visitors.items() if d != today
    )
    if today not in visitors:
        visitors[today] = {"count": 0, "ips": [], "mobile": 0, "desktop": 0, "new": 0, "returning": 0}
    day = visitors[today]
    for k in ("mobile", "desktop", "new", "returning"):
        if k not in day: day[k] = 0
    if ip_hash not in day["ips"]:
        day["ips"].append(ip_hash)
        day["count"] = len(day["ips"])
        day[device] += 1
        day["new" if is_new else "returning"] += 1
        visitors[today] = day
        save_visitors(visitors)

def load_ratings():
    if RATINGS_FILE.exists():
        try: return json.loads(RATINGS_FILE.read_text())
        except: pass
    return {"total": 0, "sum": 0, "count": 0}

def save_ratings(data):
    RATINGS_FILE.write_text(json.dumps(data))

def load_stats_file():
    if STATS_FILE.exists():
        try: return json.loads(STATS_FILE.read_text())
        except: pass
    return {"total_downloads": 0, "failed_downloads": 0, "platform_counts": {"TikTok": 0, "Instagram": 0, "Facebook": 0, "Pinterest": 0, "Other": 0}}

def save_stats_file(data):
    try:
        STATS_FILE.write_text(json.dumps(data))
    except Exception:
        pass

def load_config():
    if CONFIG_FILE.exists():
        try:
            return json.loads(CONFIG_FILE.read_text())
        except Exception:
            pass
    return {}

def save_config(data):
    CONFIG_FILE.write_text(json.dumps(data))

_UPSTASH_URL   = os.environ.get("UPSTASH_REDIS_REST_URL", "")
_UPSTASH_TOKEN = os.environ.get("UPSTASH_REDIS_REST_TOKEN", "")
_CODES_REDIS_KEY = "vip_codes"

def _redis(cmd, *args):
    if not _UPSTASH_URL:
        return None
    try:
        body = json.dumps([cmd] + list(args)).encode()
        req  = urllib.request.Request(
            _UPSTASH_URL,
            data=body,
            headers={"Authorization": f"Bearer {_UPSTASH_TOKEN}",
                     "Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=5) as r:
            return json.loads(r.read()).get("result")
    except Exception:
        return None

def load_codes():
    # Try Redis first (persists across Render restarts)
    if _UPSTASH_URL:
        raw = _redis("GET", _CODES_REDIS_KEY)
        if raw:
            try:
                return json.loads(raw)
            except Exception:
                pass
    # Fallback: local file
    if CODES_FILE.exists():
        try:
            return json.loads(CODES_FILE.read_text())
        except Exception:
            pass
    return {}

def save_codes(data):
    value = json.dumps(data, ensure_ascii=False)
    if _UPSTASH_URL:
        _redis("SET", _CODES_REDIS_KEY, value)
    try:
        CODES_FILE.write_text(value)
    except Exception:
        pass

config = load_config()
if "admin_pass" in config:
    ADMIN_PASS = config["admin_pass"]
if "admin_user" in config:
    ADMIN_USER = config["admin_user"]

SERVER_START = now()

# ===== Live Stats =====
_saved = load_stats_file()
stats = {
    "total_downloads": _saved.get("total_downloads", 0),
    "today_downloads": 0,
    "failed_downloads": _saved.get("failed_downloads", 0),
    "platform_counts": _saved.get("platform_counts", {"TikTok": 0, "Instagram": 0, "Facebook": 0, "Pinterest": 0, "Other": 0}),
    "recent_errors": [],
    "ytdlp_updated": "لم يتم بعد",
    "last_reset_date": now().date().isoformat(),
}

stats_lock = threading.Lock()


def record_download(platform, success, error_msg="", duration=0):
    with stats_lock:
        today = now().date().isoformat()
        if stats["last_reset_date"] != today:
            stats["today_downloads"] = 0
            stats["last_reset_date"] = today

        if success:
            stats["total_downloads"] += 1
            stats["today_downloads"] += 1
            key = platform if platform in stats["platform_counts"] else "Other"
            stats["platform_counts"][key] += 1
        else:
            stats["failed_downloads"] += 1
            if error_msg:
                stats["recent_errors"].insert(0, {
                    "time": now().strftime("%H:%M:%S"),
                    "error": error_msg[:120],
                    "platform": platform,
                })
                stats["recent_errors"] = stats["recent_errors"][:10]

        save_stats_file({
            "total_downloads": stats["total_downloads"],
            "failed_downloads": stats["failed_downloads"],
            "platform_counts": stats["platform_counts"],
        })

    log = load_download_log()
    log.insert(0, {
        "time": now().strftime("%Y-%m-%d %H:%M"),
        "platform": platform,
        "status": "success" if success else "failed",
        "duration": round(duration, 1),
    })
    save_download_log(log[:20])

    hour = str(now().hour)
    hourly = load_hourly_stats()
    hourly[hour] = hourly.get(hour, 0) + 1
    save_hourly_stats(hourly)

    today_str = now().date().isoformat()
    daily = load_daily_stats()
    daily[today_str] = daily.get(today_str, 0) + 1
    save_daily_stats(daily)


def get_cookies_file():
    """Write INSTAGRAM_COOKIES env var to a temp file for yt-dlp."""
    if not INSTAGRAM_COOKIES:
        return None
    import tempfile
    try:
        tmp = tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False)
        tmp.write(INSTAGRAM_COOKIES)
        tmp.close()
        return tmp.name
    except Exception:
        return None


def _write_cookies_file(content):
    if not content:
        return None
    import tempfile
    try:
        tmp = tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False)
        tmp.write(content)
        tmp.close()
        return tmp.name
    except Exception:
        return None


def get_facebook_cookies_file():
    return _write_cookies_file(FACEBOOK_COOKIES)


def apply_platform_opts(url, ydl_opts):
    """Add platform-specific yt-dlp headers/cookies."""
    url_lower = url.lower()

    if "tiktok.com" in url_lower or "vm.tiktok" in url_lower:
        tk_file = _write_cookies_file(TIKTOK_COOKIES)
        if tk_file:
            ydl_opts["cookiefile"] = tk_file
        ydl_opts.setdefault("http_headers", {}).update({
            "User-Agent": (
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                "AppleWebKit/605.1.15 (KHTML, like Gecko) "
                "Version/17.0 Mobile/15E148 Safari/604.1"
            ),
            "Referer": "https://www.tiktok.com/",
        })
        ydl_opts["extractor_args"] = {
            "tiktok": {"webpage_download": ["true"], "api": ["webapp_v2"]}
        }

    elif "facebook.com" in url_lower or "fb.watch" in url_lower:
        fb_file = _write_cookies_file(FACEBOOK_COOKIES)
        if fb_file:
            ydl_opts["cookiefile"] = fb_file
        ydl_opts.setdefault("http_headers", {}).update({
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/125.0.0.0 Safari/537.36"
            ),
            "Referer": "https://www.facebook.com/",
        })

    elif "instagram.com" in url_lower:
        ig_file = get_cookies_file()
        if ig_file:
            ydl_opts["cookiefile"] = ig_file

    return ydl_opts


def detect_platform(url):
    url = url.lower()
    if "tiktok.com" in url or "vm.tiktok.com" in url:
        return "TikTok"
    if "instagram.com" in url:
        return "Instagram"
    if "facebook.com" in url or "fb.watch" in url:
        return "Facebook"
    if "pinterest.com" in url or "pin.it" in url:
        return "Pinterest"
    return "Other"


# ===== Admin Auth =====
def verify_password(stored, provided):
    if stored and stored.startswith(("pbkdf2:", "scrypt:")):
        return check_password_hash(stored, provided)
    return stored == provided

def check_auth(username, password):
    return username == ADMIN_USER and verify_password(ADMIN_PASS, password)


def requires_auth(f):
    @functools.wraps(f)
    def decorated(*args, **kwargs):
        if not session.get("admin_logged_in"):
            if request.path.startswith("/admin/api"):
                return jsonify({"error": "غير مصرح"}), 401
            return redirect("/admin/login")
        return f(*args, **kwargs)
    return decorated


def clean_old_files():
    while True:
        now = time.time()
        for f in DOWNLOAD_DIR.iterdir():
            if f.is_file() and (now - f.stat().st_mtime) > 3600:  # 1 hour
                try:
                    f.unlink()
                except Exception:
                    pass
        time.sleep(300)


threading.Thread(target=clean_old_files, daemon=True).start()


def make_progress_hook(task_id):
    def hook(d):
        if d["status"] == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate", 0)
            downloaded = d.get("downloaded_bytes", 0)
            percent = int((downloaded / total) * 100) if total else 0
            progress_store[task_id] = {
                "status": "downloading",
                "percent": percent,
                "speed": d.get("_speed_str", ""),
                "eta": d.get("_eta_str", ""),
            }
        elif d["status"] == "finished":
            progress_store[task_id]["status"] = "processing"
    return hook


# ===== Routes =====
@app.route("/static/icons/<size>")
def app_icon(size):
    icon_path = Path("static/images/app-icon.png")
    if icon_path.exists():
        return send_file(str(icon_path), mimetype="image/png")
    from flask import make_response
    resp = make_response(b"")
    resp.status_code = 404
    return resp


@app.route("/download-app")
def download_android_app():
    return send_file("static/android-app.zip", as_attachment=True, download_name="android-app.zip")


@app.route("/.well-known/assetlinks.json")
def asset_links():
    sha256 = os.environ.get("ANDROID_CERT_SHA256", "REPLACE_WITH_YOUR_SHA256_FINGERPRINT")
    data = [{
        "relation": ["delegate_permission/common.handle_all_urls"],
        "target": {
            "namespace": "android_app",
            "package_name": "com.nazzilhaplus.app",
            "sha256_cert_fingerprints": [sha256]
        }
    }]
    return jsonify(data)


@app.route("/robots.txt")
def robots_txt():
    content = """User-agent: *
Allow: /
Disallow: /admin
Disallow: /admin/
Sitemap: https://www.vip-dl.com/sitemap.xml"""
    return content, 200, {"Content-Type": "text/plain"}


@app.route("/sitemap.xml")
def sitemap_xml():
    xml = '''<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url>
    <loc>https://www.vip-dl.com/</loc>
    <changefreq>daily</changefreq>
    <priority>1.0</priority>
  </url>
</urlset>'''
    return xml, 200, {"Content-Type": "application/xml"}


@app.route("/ads.txt")
def ads_txt():
    return "google.com, pub-9098461798177099, DIRECT, f08c47fec0942fa0", 200, {"Content-Type": "text/plain"}


@app.route("/api/public-stats")
def public_stats():
    r = load_ratings()
    avg = round(r["sum"] / r["count"], 1) if r["count"] > 0 else 5.0
    return jsonify({
        "total_downloads": stats["total_downloads"],
        "rating_avg": avg,
        "rating_count": r["count"],
    })


@app.route("/api/rate", methods=["POST"])
@limiter.limit("5 per minute")
def submit_rating():
    data = request.get_json() or {}
    stars = int(data.get("stars", 0))
    if stars < 1 or stars > 5:
        return jsonify({"error": "تقييم غير صالح"}), 400
    r = load_ratings()
    r["count"] += 1
    r["sum"] += stars
    save_ratings(r)
    avg = round(r["sum"] / r["count"], 1)
    return jsonify({"message": "شكراً على تقييمك!", "avg": avg, "count": r["count"]})


@app.route("/")
def index():
    cfg = load_settings()
    if cfg.get("maintenance_mode"):
        return render_template("maintenance.html"), 503
    record_visit(get_remote_address(), request.headers.get("User-Agent", ""))
    return render_template("index.html", stripe_link=STRIPE_PAYMENT_LINK, settings=cfg)


@app.route("/privacy")
def privacy():
    return render_template("privacy.html")


@app.route("/how-to-use")
def how_to_use():
    return render_template("how-to-use.html")


BLOG_ARTICLES = [
    {
        "slug": "download-tiktok-without-watermark",
        "title": "كيف تحمّل فيديوهات تيك توك بدون علامة مائية 2025",
        "description": "دليل شامل لتحميل فيديوهات TikTok بجودة عالية وبدون علامة مائية على أي جهاز.",
        "emoji": "🎵",
        "date": "2025-05-01",
        "content": """
<h2>لماذا تريد تحميل فيديوهات تيك توك؟</h2>
<p>تيك توك من أكثر منصات التواصل الاجتماعي شعبية في العالم، ويشارك المستخدمون يومياً آلاف الفيديوهات الترفيهية والتعليمية. كثيراً ما تريد حفظ فيديو مفضل لمشاهدته لاحقاً بدون إنترنت، أو مشاركته مع أصدقاء لا يستخدمون التطبيق.</p>

<h2>المشكلة مع التحميل المباشر</h2>
<p>عندما تحمّل فيديو مباشرة من تطبيق تيك توك، يُضاف عليه تلقائياً اسم المستخدم والعلامة المائية الخاصة بالمنصة، مما يجعل الفيديو يبدو غير احترافي إذا أردت مشاركته في مكان آخر.</p>

<h2>الحل: استخدام نزلها بلس</h2>
<p>موقع نزلها بلس يتيح لك تحميل فيديوهات تيك توك بجودة عالية وبدون أي علامة مائية، وذلك بخطوات بسيطة جداً.</p>

<h2>خطوات التحميل</h2>
<ol>
<li><strong>افتح تطبيق تيك توك</strong> على هاتفك أو جهازك.</li>
<li><strong>ابحث عن الفيديو</strong> الذي تريد تحميله.</li>
<li><strong>اضغط على مشاركة</strong> ثم اختر "نسخ الرابط".</li>
<li><strong>افتح موقع vip-dl.com</strong> في متصفحك.</li>
<li><strong>الصق الرابط</strong> في خانة البحث واضغط تحميل.</li>
<li><strong>انتظر معالجة الفيديو</strong> ثم اضغط "تحميل الفيديو".</li>
</ol>

<h2>نصائح مهمة</h2>
<ul>
<li>تأكد من أن الفيديو عام وليس خاصاً قبل محاولة التحميل.</li>
<li>اختر أعلى جودة متاحة للحصول على صورة واضحة.</li>
<li>احترم حقوق الملكية الفكرية واستخدم الفيديوهات للأغراض الشخصية فقط.</li>
<li>يمكنك تحميل عدة فيديوهات متتالية بدون أي قيود.</li>
</ul>

<h2>هل التحميل مجاني؟</h2>
<p>نعم، موقع نزلها بلس مجاني 100% ولا يتطلب أي تسجيل أو اشتراك. يمكنك تحميل عدد غير محدود من الفيديوهات مجاناً.</p>

<h2>أين أجد الفيديو بعد التحميل؟</h2>
<p>بعد اكتمال التحميل ستجد الفيديو في:</p>
<ul>
<li><strong>على الأندرويد:</strong> مجلد Downloads في تطبيق الملفات.</li>
<li><strong>على الآيفون:</strong> تطبيق الملفات أو معرض الصور مباشرة.</li>
<li><strong>على الكمبيوتر:</strong> مجلد التنزيلات الافتراضي.</li>
</ul>
"""
    },
    {
        "slug": "download-instagram-reels",
        "title": "أفضل طريقة لتحميل ريلز إنستقرام على هاتفك",
        "description": "تعرف على أسهل طريقة لتحميل مقاطع Reels من إنستقرام بجودة HD بدون برامج إضافية.",
        "emoji": "📸",
        "date": "2025-05-05",
        "content": """
<h2>ما هي ريلز إنستقرام؟</h2>
<p>ريلز إنستقرام هي مقاطع فيديو قصيرة مدتها من 15 ثانية إلى 90 ثانية، أصبحت من أكثر أنواع المحتوى انتشاراً على المنصة. تجد فيها محتوى تعليمي، ترفيهي، وصفات طبخ، تمارين رياضية، وأكثر.</p>

<h2>لماذا لا يتيح إنستقرام التحميل المباشر؟</h2>
<p>إنستقرام لا يوفر خاصية تحميل الفيديوهات مباشرة لحماية حقوق المحتوى. لذلك يحتاج المستخدمون لاستخدام أدوات خارجية لتحميل ما يريدون حفظه.</p>

<h2>كيف تحمّل ريلز إنستقرام بخطوات سهلة</h2>
<ol>
<li>افتح إنستقرام وابحث عن الريل الذي تريده.</li>
<li>اضغط على النقاط الثلاث (⋯) في أعلى الفيديو.</li>
<li>اختر <strong>"نسخ الرابط"</strong>.</li>
<li>توجه لموقع <strong>vip-dl.com</strong>.</li>
<li>الصق الرابط في الحقل المخصص.</li>
<li>اضغط على زر التحميل وانتظر المعالجة.</li>
<li>اضغط "تحميل الفيديو" لحفظه على جهازك.</li>
</ol>

<h2>تحميل صور إنستقرام أيضاً</h2>
<p>لا يقتصر الأمر على الفيديوهات فقط — يمكنك أيضاً تحميل الصور من إنستقرام بنفس الطريقة. فقط انسخ رابط المنشور وضعه في الموقع.</p>

<h2>هل يعمل مع الحسابات الخاصة؟</h2>
<p>لا، لا يمكن تحميل محتوى الحسابات الخاصة. يعمل الموقع فقط مع الحسابات العامة والمحتوى المتاح للجميع.</p>

<h2>نصائح للحصول على أفضل جودة</h2>
<ul>
<li>تأكد من اتصالك بالإنترنت قبل الضغط على زر التحميل.</li>
<li>افتح الفيديو في إنستقرام قبل نسخ الرابط للتأكد من صحته.</li>
<li>إذا لم يعمل الرابط، جرب فتح الفيديو في المتصفح ونسخ الرابط من شريط العنوان.</li>
</ul>
"""
    },
    {
        "slug": "download-facebook-videos",
        "title": "تحميل فيديوهات فيسبوك بجودة عالية - دليل شامل",
        "description": "طريقة سهلة وسريعة لتحميل أي فيديو من فيسبوك سواء من الصفحات أو المجموعات.",
        "emoji": "📘",
        "date": "2025-05-10",
        "content": """
<h2>فيسبوك وتحميل الفيديوهات</h2>
<p>فيسبوك من أقدم وأكبر منصات التواصل الاجتماعي، ويحتوي على ملايين الفيديوهات في مختلف المجالات. سواء كانت فيديوهات إخبارية، رياضية، ترفيهية، أو تعليمية — يمكنك الآن تحميل أي منها بسهولة.</p>

<h2>طريقة نسخ رابط فيديو فيسبوك</h2>
<p>هناك عدة طرق لنسخ رابط فيديو فيسبوك:</p>

<h3>من التطبيق:</h3>
<ol>
<li>افتح الفيديو في تطبيق فيسبوك.</li>
<li>اضغط على النقاط الثلاث (⋯).</li>
<li>اختر "نسخ الرابط".</li>
</ol>

<h3>من المتصفح:</h3>
<ol>
<li>افتح الفيديو في المتصفح.</li>
<li>انسخ الرابط من شريط العنوان مباشرة.</li>
</ol>

<h2>خطوات التحميل عبر نزلها بلس</h2>
<ol>
<li>انسخ رابط الفيديو من فيسبوك.</li>
<li>افتح موقع vip-dl.com.</li>
<li>الصق الرابط في الخانة المخصصة.</li>
<li>اضغط على زر "تحميل".</li>
<li>انتظر معالجة الفيديو.</li>
<li>اختر الجودة المناسبة واضغط "تحميل الفيديو".</li>
</ol>

<h2>أنواع محتوى فيسبوك التي يمكن تحميلها</h2>
<ul>
<li>فيديوهات المنشورات العادية.</li>
<li>فيديوهات الصفحات العامة.</li>
<li>فيديوهات المجموعات العامة.</li>
<li>ريلز فيسبوك.</li>
</ul>

<h2>ملاحظات مهمة</h2>
<p>لا يمكن تحميل فيديوهات المنشورات الخاصة أو فيديوهات البث المباشر الخاصة. كما يُنصح دائماً باحترام حقوق الملكية الفكرية وعدم إعادة نشر المحتوى دون إذن أصحابه.</p>
"""
    },
    {
        "slug": "best-video-download-tips",
        "title": "نصائح احترافية لتحميل الفيديوهات بأعلى جودة",
        "description": "اكتشف أهم النصائح والحيل للحصول على أفضل جودة عند تحميل الفيديوهات من الإنترنت.",
        "emoji": "💡",
        "date": "2025-05-15",
        "content": """
<h2>أهمية جودة الفيديو عند التحميل</h2>
<p>عندما تحمّل فيديو من الإنترنت، جودة الملف النهائي تعتمد على عدة عوامل. فهم هذه العوامل يساعدك على الحصول على أفضل نتيجة ممكنة في كل مرة.</p>

<h2>1. اختر أعلى دقة متاحة</h2>
<p>معظم مواقع التواصل الاجتماعي تتيح فيديوهات بدقات مختلفة (480p، 720p، 1080p). دائماً اختر الأعلى إذا كان لديك مساحة كافية على جهازك.</p>

<h2>2. اتصال إنترنت مستقر</h2>
<p>لضمان تحميل الفيديو كاملاً وبجودة عالية، تأكد من اتصالك بشبكة إنترنت مستقرة. استخدام الواي فاي أفضل من بيانات الجوال لتحميل الفيديوهات الكبيرة.</p>

<h2>3. لا تقاطع عملية التحميل</h2>
<p>اترك الصفحة مفتوحة حتى يكتمل التحميل. إغلاق المتصفح أو التنقل لصفحة أخرى قد يوقف عملية التحميل.</p>

<h2>4. تحقق من جودة المصدر الأصلي</h2>
<p>إذا كان الفيديو الأصلي رُفع بجودة منخفضة، فلن تستطيع الحصول على جودة أعلى منه عند التحميل. الجودة النهائية تعتمد على جودة المصدر.</p>

<h2>5. استخدم الموقع الصحيح</h2>
<p>موقع نزلها بلس متخصص في تحميل الفيديوهات بأعلى جودة متاحة من المنصات الأصلية. يدعم تيك توك وإنستقرام وفيسبوك وبينتريست وغيرها.</p>

<h2>6. مساحة التخزين</h2>
<p>قبل التحميل، تأكد من وجود مساحة كافية على جهازك. فيديو بجودة 1080p قد يأخذ بين 50 و500 ميغابايت حسب مدته.</p>

<h2>7. تنظيم ملفاتك</h2>
<p>أنشئ مجلداً مخصصاً للفيديوهات المحمّلة لتسهيل إيجادها لاحقاً. يحفظ تطبيق نزلها بلس الفيديوهات تلقائياً في مجلد "NazzilhaPlus" داخل Downloads.</p>

<h2>أكثر الأخطاء شيوعاً عند التحميل</h2>
<ul>
<li><strong>رابط خاطئ:</strong> تأكد من نسخ الرابط كاملاً.</li>
<li><strong>حساب خاص:</strong> الفيديو من حساب خاص لا يمكن تحميله.</li>
<li><strong>الفيديو محذوف:</strong> إذا حذف صاحبه الفيديو، لن تتمكن من تحميله.</li>
<li><strong>انقطاع الإنترنت:</strong> يؤدي لفشل التحميل، أعد المحاولة.</li>
</ul>
"""
    },
    {
        "slug": "download-pinterest-videos",
        "title": "كيف تحمّل فيديوهات وصور بينتريست بسهولة",
        "description": "دليل سريع لتحميل فيديوهات وصور بينتريست على هاتفك أو كمبيوترك في ثوانٍ.",
        "emoji": "📌",
        "date": "2025-05-18",
        "content": """
<h2>بينتريست - منصة الإلهام والأفكار</h2>
<p>بينتريست منصة فريدة تجمع بين الصور والفيديوهات الإبداعية في مجالات الديكور، الطبخ، الموضة، الفن، والأفكار الإبداعية. كثيراً ما تجد فيها محتوى رائعاً تريد حفظه للرجوع إليه لاحقاً.</p>

<h2>لماذا يصعب التحميل من بينتريست؟</h2>
<p>بينتريست لا يوفر خيار تحميل مباشر للفيديوهات، ويقتصر على حفظ الصور في بعض الحالات. لذلك تحتاج لأداة متخصصة مثل نزلها بلس للتحميل.</p>

<h2>طريقة نسخ رابط بينتريست</h2>
<h3>من التطبيق:</h3>
<ol>
<li>افتح الصورة أو الفيديو في تطبيق بينتريست.</li>
<li>اضغط على أيقونة المشاركة (Share).</li>
<li>اختر "نسخ الرابط" أو "Copy Link".</li>
</ol>

<h3>من المتصفح:</h3>
<ol>
<li>افتح المحتوى في متصفحك.</li>
<li>انسخ الرابط من شريط العنوان.</li>
</ol>

<h2>تحميل محتوى بينتريست عبر نزلها بلس</h2>
<ol>
<li>انسخ رابط الصورة أو الفيديو من بينتريست.</li>
<li>افتح موقع vip-dl.com.</li>
<li>الصق الرابط في خانة التحميل.</li>
<li>اضغط تحميل وانتظر المعالجة.</li>
<li>اضغط "تحميل الفيديو" أو "تحميل الصورة".</li>
</ol>

<h2>استخدامات تحميل محتوى بينتريست</h2>
<ul>
<li>حفظ أفكار الديكور والتصميم لمشاريعك.</li>
<li>الاحتفاظ بوصفات الطبخ المفضلة.</li>
<li>جمع أفكار الموضة والإطلالات.</li>
<li>حفظ الصور الإلهامية للعمل والإبداع.</li>
</ul>

<h2>تنبيه مهم</h2>
<p>احرص دائماً على احترام حقوق الملكية الفكرية. المحتوى المحمّل للاستخدام الشخصي مقبول، أما إعادة نشره دون إذن صاحبه فقد يكون انتهاكاً لحقوق النشر.</p>
"""
    },
]


@app.route("/blog")
def blog():
    return render_template("blog.html", articles=BLOG_ARTICLES)


@app.route("/blog/<slug>")
def blog_article(slug):
    article = next((a for a in BLOG_ARTICLES if a["slug"] == slug), None)
    if not article:
        return render_template("404.html"), 404
    return render_template("article.html", article=article, articles=BLOG_ARTICLES)


@app.route("/about")
def about():
    return render_template("about.html")


@app.route("/api/app-ping", methods=["POST"])
def app_ping():
    data = request.get_json() or {}
    device_id = (data.get("device_id") or "").strip()[:64]
    if not device_id:
        return jsonify({"ok": False}), 400
    installs = json.loads(APP_INSTALLS_FILE.read_text()) if APP_INSTALLS_FILE.exists() else {"devices": [], "total": 0}
    if device_id not in installs["devices"]:
        installs["devices"].append(device_id)
        installs["total"] = len(installs["devices"])
        APP_INSTALLS_FILE.write_text(json.dumps(installs))
    return jsonify({"ok": True, "total": installs["total"]})


@app.route("/admin/api/app-installs")
@requires_auth
def admin_app_installs():
    installs = json.loads(APP_INSTALLS_FILE.read_text()) if APP_INSTALLS_FILE.exists() else {"devices": [], "total": 0}
    return jsonify({"total": installs["total"]})


# ===== Admin Dashboard =====
@app.route("/admin")
@requires_auth
def admin():
    return render_template("admin.html")


@app.route("/admin/api/visitor-stats")
@requires_auth
def admin_visitor_stats():
    visitors = load_visitors()
    result = []
    for i in range(29, -1, -1):
        date = (now() - timedelta(days=i)).date().isoformat()
        count = visitors.get(date, {}).get("count", 0)
        result.append({"date": date, "count": count})
    today = now().date().isoformat()
    yesterday = (now() - timedelta(days=1)).date().isoformat()
    today_count = visitors.get(today, {}).get("count", 0)
    yesterday_count = visitors.get(yesterday, {}).get("count", 0)
    week_total = sum(visitors.get((now() - timedelta(days=i)).date().isoformat(), {}).get("count", 0) for i in range(7))
    return jsonify({"days": result, "today": today_count, "yesterday": yesterday_count, "week": week_total})


@app.route("/admin/api/subscriber-stats")
@requires_auth
def subscriber_stats():
    codes = load_codes()
    current_time = now()
    total = len(codes)
    active = 0
    expired = 0
    unused = 0
    for v in codes.values():
        if not v["used"]:
            unused += 1
        elif v.get("expires_at"):
            try:
                exp = datetime.strptime(v["expires_at"], "%Y-%m-%d %H:%M")
                if current_time > exp:
                    expired += 1
                else:
                    active += 1
            except Exception:
                active += 1
        else:
            active += 1
    return jsonify({"total": total, "active": active, "expired": expired, "unused": unused})


@app.route("/admin/api/stats")
@requires_auth
def admin_stats():
    uptime_sec = int((now() - SERVER_START).total_seconds())
    hours = uptime_sec // 3600
    minutes = (uptime_sec % 3600) // 60
    uptime_str = f"{hours} ساعة و {minutes} دقيقة"

    dl_files = list(DOWNLOAD_DIR.glob("*"))
    storage_mb = round(sum(f.stat().st_size for f in dl_files if f.is_file()) / 1024 / 1024, 2)

    try:
        ytdlp_ver = yt_dlp.version.__version__
    except Exception:
        ytdlp_ver = "غير معروف"

    active = sum(1 for v in progress_store.values() if v.get("status") in ("downloading", "processing", "starting"))

    return jsonify({
        "status": "online",
        "uptime": uptime_str,
        "total_downloads": stats["total_downloads"],
        "today_downloads": stats["today_downloads"],
        "failed_downloads": stats["failed_downloads"],
        "platform_counts": stats["platform_counts"],
        "active_tasks": active,
        "storage_mb": storage_mb,
        "temp_files": len(dl_files),
        "ytdlp_version": ytdlp_ver,
        "ytdlp_updated": stats["ytdlp_updated"],
        "recent_errors": stats["recent_errors"],
        "server_time": now().strftime("%Y-%m-%d %H:%M:%S"),
    })


@app.route("/admin/login", methods=["GET", "POST"])
@limiter.limit("10 per minute")
def admin_login():
    ip = get_remote_address()
    error = ""
    if request.method == "POST":
        locked, mins = is_locked(ip)
        if locked:
            error = f"تم تجميد تسجيل الدخول لمدة {mins} دقيقة بسبب محاولات متعددة"
        else:
            u = request.form.get("username", "")
            p = request.form.get("password", "")
            if check_auth(u, p):
                clear_login_attempts(ip)
                session["admin_logged_in"] = True
                return redirect("/admin")
            record_failed_login(ip)
            locked2, mins2 = is_locked(ip)
            if locked2:
                error = f"تم تجميد الحساب لمدة {mins2} دقيقة بسبب محاولات متعددة"
            else:
                attempts_left = 5 - login_attempts.get(ip, {}).get("count", 0)
                error = f"اسم المستخدم أو كلمة السر غير صحيحة ({attempts_left} محاولات متبقية)"

    return Response(f"""<!DOCTYPE html><html lang="ar" dir="rtl">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>تسجيل دخول — نزلها بلس</title>
<link href="https://fonts.googleapis.com/css2?family=Cairo:wght@400;700;900&display=swap" rel="stylesheet">
<style>
*{{box-sizing:border-box;margin:0;padding:0}}
body{{font-family:'Cairo',sans-serif;background:#0a0a0f;color:#f0f0f8;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:1rem}}
.card{{background:#16161f;border:1px solid #2a2a3a;border-radius:20px;padding:2.5rem 2rem;width:100%;max-width:380px;box-shadow:0 20px 60px rgba(0,0,0,.6)}}
.logo{{text-align:center;font-size:1.5rem;font-weight:900;margin-bottom:.4rem}}
.logo span{{color:#a855f7}}
.sub{{text-align:center;color:#8888aa;font-size:.85rem;margin-bottom:2rem}}
label{{font-size:.8rem;color:#8888aa;display:block;margin-bottom:.3rem}}
input{{width:100%;background:#111118;border:1.5px solid #2a2a3a;border-radius:10px;padding:.8rem 1rem;color:#f0f0f8;font-family:inherit;font-size:1rem;outline:none;margin-bottom:1rem;transition:border-color .2s}}
input:focus{{border-color:#7c3aed}}
.btn{{width:100%;padding:.9rem;background:linear-gradient(135deg,#7c3aed,#a855f7);color:#fff;border:none;border-radius:12px;font-family:inherit;font-size:1rem;font-weight:700;cursor:pointer;transition:opacity .2s}}
.btn:hover{{opacity:.9}}
.error{{background:rgba(239,68,68,.1);border:1px solid rgba(239,68,68,.3);border-radius:8px;padding:.6rem 1rem;color:#fca5a5;font-size:.85rem;margin-bottom:1rem;text-align:center}}
.forgot{{display:block;text-align:center;margin-top:1rem;color:#8888aa;font-size:.82rem;text-decoration:none}}
.forgot:hover{{color:#f0f0f8}}
</style></head>
<body>
<div class="card">
  <div class="logo">⚙️ لوحة تحكم <span>نزلها بلس</span></div>
  <div class="sub">تسجيل دخول المدير</div>
  {'<div class="error">⚠️ ' + error + '</div>' if error else ''}
  <form method="POST">
    <label>اسم المستخدم</label>
    <input type="text" name="username" placeholder="admin" autocomplete="username" required />
    <label>كلمة السر</label>
    <input type="password" name="password" placeholder="••••••••" autocomplete="current-password" required />
    <button type="submit" class="btn">تسجيل الدخول</button>
  </form>
  <a href="/admin/forgot" class="forgot">🔑 نسيت كلمة السر؟</a>
</div>
</body></html>""", mimetype="text/html")


@app.route("/admin/logout")
def admin_logout():
    session.clear()
    return redirect("/admin/login")


@app.route("/admin/emergency")
@limiter.limit("5 per hour")
def admin_emergency():
    secret = request.args.get("secret", "")
    new_pass = request.args.get("new_pass", "")

    if not RESET_SECRET:
        return Response("<h2 style='font-family:sans-serif;color:red'>RESET_SECRET غير مضبوط في المتغيرات</h2>", mimetype="text/html")

    if secret != RESET_SECRET:
        return Response(f"""<!DOCTYPE html><html lang="ar" dir="rtl">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>إعادة تعيين طارئة</title>
<link href="https://fonts.googleapis.com/css2?family=Cairo:wght@400;700;900&display=swap" rel="stylesheet">
<style>*{{box-sizing:border-box;margin:0;padding:0}}body{{font-family:'Cairo',sans-serif;background:#0a0a0f;color:#f0f0f8;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:1rem}}.card{{background:#16161f;border:1px solid #2a2a3a;border-radius:20px;padding:2rem;width:100%;max-width:400px}}h2{{margin-bottom:.8rem}}p{{color:#8888aa;font-size:.9rem;margin-bottom:1rem}}input{{width:100%;background:#111118;border:1.5px solid #2a2a3a;border-radius:10px;padding:.8rem 1rem;color:#f0f0f8;font-family:inherit;font-size:1rem;outline:none;margin-bottom:.8rem}}.btn{{width:100%;padding:.9rem;background:linear-gradient(135deg,#7c3aed,#a855f7);color:#fff;border:none;border-radius:12px;font-family:inherit;font-size:1rem;font-weight:700;cursor:pointer}}</style>
</head><body><div class="card">
<h2>🔑 إعادة تعيين طارئة</h2>
<p>أدخل الكلمة السرية وكلمة السر الجديدة</p>
<input id="s" type="password" placeholder="الكلمة السرية" />
<input id="p" type="password" placeholder="كلمة السر الجديدة" />
<button class="btn" onclick="go()">إعادة التعيين</button>
<script>
function go(){{
  const s=document.getElementById('s').value;
  const p=document.getElementById('p').value;
  if(!s||!p){{alert('أدخل جميع الحقول');return;}}
  window.location='/admin/emergency?secret='+encodeURIComponent(s)+'&new_pass='+encodeURIComponent(p);
}}
</script>
</div></body></html>""", mimetype="text/html")

    global ADMIN_PASS
    if new_pass and len(new_pass) >= 6:
        ADMIN_PASS = generate_password_hash(new_pass)
        cfg = load_config()
        cfg["admin_pass"] = ADMIN_PASS
        save_config(cfg)
        session["admin_logged_in"] = True
        return Response(f"""<!DOCTYPE html><html lang="ar" dir="rtl">
<head><meta charset="UTF-8"><title>تم</title>
<link href="https://fonts.googleapis.com/css2?family=Cairo:wght@700&display=swap" rel="stylesheet">
<style>body{{font-family:'Cairo',sans-serif;background:#0a0a0f;color:#f0f0f8;display:flex;align-items:center;justify-content:center;min-height:100vh;flex-direction:column;gap:1rem}}</style>
</head><body>
<div style="font-size:3rem">✅</div>
<h2>تم تغيير كلمة السر بنجاح</h2>
<a href="/admin" style="color:#a855f7;font-size:1rem">الذهاب للوحة التحكم</a>
</body></html>""", mimetype="text/html")

    return Response(f"""<!DOCTYPE html><html lang="ar" dir="rtl">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>إعادة تعيين طارئة</title>
<link href="https://fonts.googleapis.com/css2?family=Cairo:wght@400;700;900&display=swap" rel="stylesheet">
<style>*{{box-sizing:border-box;margin:0;padding:0}}body{{font-family:'Cairo',sans-serif;background:#0a0a0f;color:#f0f0f8;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:1rem}}.card{{background:#16161f;border:1px solid #2a2a3a;border-radius:20px;padding:2rem;width:100%;max-width:400px}}h2{{margin-bottom:.8rem}}p{{color:#10b981;font-size:.9rem;margin-bottom:1rem}}input{{width:100%;background:#111118;border:1.5px solid #2a2a3a;border-radius:10px;padding:.8rem 1rem;color:#f0f0f8;font-family:inherit;font-size:1rem;outline:none;margin-bottom:.8rem}}.btn{{width:100%;padding:.9rem;background:linear-gradient(135deg,#059669,#10b981);color:#fff;border:none;border-radius:12px;font-family:inherit;font-size:1rem;font-weight:700;cursor:pointer}}</style>
</head><body><div class="card">
<h2>🔑 إعادة تعيين طارئة</h2>
<p>✅ الكلمة السرية صحيحة — أدخل كلمة السر الجديدة</p>
<input id="p" type="password" placeholder="كلمة السر الجديدة (6 أحرف على الأقل)" />
<button class="btn" onclick="go()">حفظ كلمة السر الجديدة</button>
<script>
function go(){{
  const p=document.getElementById('p').value;
  if(p.length<6){{alert('6 أحرف على الأقل');return;}}
  window.location='/admin/emergency?secret={secret}&new_pass='+encodeURIComponent(p);
}}
</script>
</div></body></html>""", mimetype="text/html")


def send_reset_email(token):
    reset_url = f"https://www.vip-dl.com/admin/reset?token={token}"
    body = f"""مرحباً،

طُلب إعادة تعيين كلمة سر لوحة تحكم نزلها بلس.

اضغط على الرابط التالي لإعادة تعيين كلمة السر:
{reset_url}

الرابط صالح لمدة 30 دقيقة فقط.
إذا لم تطلب ذلك، تجاهل هذا الإيميل.

— نزلها بلس
"""
    msg = MIMEMultipart()
    msg["Subject"] = "🔐 إعادة تعيين كلمة سر لوحة التحكم"
    msg["From"] = SMTP_USER
    msg["To"] = ADMIN_EMAIL
    msg.attach(MIMEText(body, "plain", "utf-8"))

    with smtplib.SMTP_SSL("smtp.gmail.com", 465, timeout=15) as server:
        server.login(SMTP_USER, SMTP_PASS)
        server.send_message(msg)


@app.route("/admin/forgot")
def admin_forgot():
    return Response("""<!DOCTYPE html><html lang="ar" dir="rtl">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>نسيت كلمة السر</title>
<link href="https://fonts.googleapis.com/css2?family=Cairo:wght@400;700;900&display=swap" rel="stylesheet">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:'Cairo',sans-serif;background:#0a0a0f;color:#f0f0f8;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:1rem}
.card{background:#16161f;border:1px solid #2a2a3a;border-radius:16px;padding:2rem;width:100%;max-width:400px;box-shadow:0 8px 40px rgba(0,0,0,.5)}
h2{font-size:1.3rem;margin-bottom:.5rem}
p{color:#8888aa;font-size:.9rem;margin-bottom:1.5rem}
input{width:100%;background:#111118;border:1.5px solid #2a2a3a;border-radius:10px;padding:.8rem 1rem;color:#f0f0f8;font-family:inherit;font-size:1rem;outline:none;margin-bottom:1rem;direction:ltr}
button{width:100%;padding:.9rem;background:linear-gradient(135deg,#7c3aed,#a855f7);color:#fff;border:none;border-radius:12px;font-family:inherit;font-size:1rem;font-weight:700;cursor:pointer}
#msg{text-align:center;font-size:.9rem;margin-top:.8rem;min-height:1.2rem}
a{color:#8888aa;font-size:.85rem;display:block;text-align:center;margin-top:1rem;text-decoration:none}
</style></head>
<body>
<div class="card">
  <h2>🔐 نسيت كلمة السر؟</h2>
  <p>سنرسل رابط إعادة التعيين إلى إيميلك المسجل حصراً</p>
  <input id="email" type="email" placeholder="أدخل إيميلك" />
  <button onclick="sendReset()">إرسال رابط الإعادة</button>
  <div id="msg"></div>
  <a href="/admin">← العودة لتسجيل الدخول</a>
</div>
<script>
async function sendReset() {
  const email = document.getElementById('email').value.trim();
  const msg = document.getElementById('msg');
  if (!email) { msg.style.color='#fca5a5'; msg.textContent='أدخل الإيميل'; return; }
  msg.style.color='#8888aa'; msg.textContent='جاري الإرسال...';
  try {
    const res = await fetch('/admin/api/send-reset', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({email})
    });
    const d = await res.json();
    if (res.ok) { msg.style.color='#10b981'; msg.textContent=d.message; }
    else { msg.style.color='#fca5a5'; msg.textContent=d.error; }
  } catch { msg.style.color='#fca5a5'; msg.textContent='خطأ في الاتصال'; }
}
</script>
</body></html>""", mimetype="text/html")


@app.route("/admin/api/send-reset", methods=["POST"])
def send_reset():
    email = ((request.get_json() or {}).get("email", "")).strip().lower()
    if email != ADMIN_EMAIL.lower():
        return jsonify({"error": "هذا الإيميل غير مسجل"}), 403

    if not SMTP_USER or not SMTP_PASS:
        return jsonify({"error": "لم يتم إعداد خدمة الإيميل بعد"}), 500

    token = secrets.token_urlsafe(32)
    reset_tokens[token] = {"expires": now() + timedelta(minutes=30)}

    try:
        send_reset_email(token)
        return jsonify({"message": "✅ تم إرسال الرابط إلى إيميلك"})
    except Exception as e:
        del reset_tokens[token]
        return jsonify({"error": "فشل إرسال الإيميل، تحقق من إعدادات SMTP"}), 500


@app.route("/admin/reset")
def admin_reset_page():
    token = request.args.get("token", "")
    valid = token in reset_tokens and now() < reset_tokens[token]["expires"]
    status = "valid" if valid else "invalid"
    return Response(f"""<!DOCTYPE html><html lang="ar" dir="rtl">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>إعادة تعيين كلمة السر</title>
<link href="https://fonts.googleapis.com/css2?family=Cairo:wght@400;700;900&display=swap" rel="stylesheet">
<style>
*{{box-sizing:border-box;margin:0;padding:0}}
body{{font-family:'Cairo',sans-serif;background:#0a0a0f;color:#f0f0f8;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:1rem}}
.card{{background:#16161f;border:1px solid #2a2a3a;border-radius:16px;padding:2rem;width:100%;max-width:400px;box-shadow:0 8px 40px rgba(0,0,0,.5)}}
h2{{font-size:1.3rem;margin-bottom:.5rem}}
p{{color:#8888aa;font-size:.9rem;margin-bottom:1.5rem}}
input{{width:100%;background:#111118;border:1.5px solid #2a2a3a;border-radius:10px;padding:.8rem 1rem;color:#f0f0f8;font-family:inherit;font-size:1rem;outline:none;margin-bottom:1rem}}
button{{width:100%;padding:.9rem;background:linear-gradient(135deg,#7c3aed,#a855f7);color:#fff;border:none;border-radius:12px;font-family:inherit;font-size:1rem;font-weight:700;cursor:pointer}}
#msg{{text-align:center;font-size:.9rem;margin-top:.8rem;min-height:1.2rem}}
a{{color:#8888aa;font-size:.85rem;display:block;text-align:center;margin-top:1rem;text-decoration:none}}
</style></head>
<body>
<div class="card">
{'<h2>❌ الرابط منتهي أو غير صالح</h2><p>اطلب رابطاً جديداً</p><a href="/admin/forgot">← طلب رابط جديد</a>' if status == 'invalid' else f'''
  <h2>🔐 إعادة تعيين كلمة السر</h2>
  <p>أدخل كلمة السر الجديدة</p>
  <input id="p1" type="password" placeholder="كلمة السر الجديدة" />
  <input id="p2" type="password" placeholder="تأكيد كلمة السر" />
  <button onclick="doReset()">حفظ كلمة السر الجديدة</button>
  <div id="msg"></div>
  <script>
  async function doReset() {{
    const p1 = document.getElementById('p1').value;
    const p2 = document.getElementById('p2').value;
    const msg = document.getElementById('msg');
    if (p1.length < 6) {{ msg.style.color='#fca5a5'; msg.textContent='6 أحرف على الأقل'; return; }}
    if (p1 !== p2) {{ msg.style.color='#fca5a5'; msg.textContent='كلمتا السر غير متطابقتين'; return; }}
    msg.style.color='#8888aa'; msg.textContent='جاري الحفظ...';
    const res = await fetch('/admin/api/do-reset', {{
      method:'POST', headers:{{'Content-Type':'application/json'}},
      body: JSON.stringify({{token: '{token}', new_pass: p1}})
    }});
    const d = await res.json();
    if (res.ok) {{ msg.style.color='#10b981'; msg.textContent=d.message; setTimeout(()=>window.location='/admin', 2000); }}
    else {{ msg.style.color='#fca5a5'; msg.textContent=d.error; }}
  }}
  </script>
'''}
</div>
</body></html>""", mimetype="text/html")


@app.route("/admin/api/do-reset", methods=["POST"])
def do_reset():
    global ADMIN_PASS
    data = request.get_json() or {}
    token = data.get("token", "")
    new_pass = data.get("new_pass", "")

    if token not in reset_tokens or now() > reset_tokens[token]["expires"]:
        return jsonify({"error": "الرابط منتهي أو غير صالح"}), 403
    if len(new_pass) < 6:
        return jsonify({"error": "كلمة السر يجب أن تكون 6 أحرف على الأقل"}), 400

    ADMIN_PASS = generate_password_hash(new_pass)
    del reset_tokens[token]

    cfg = load_config()
    cfg["admin_pass"] = ADMIN_PASS
    save_config(cfg)

    return jsonify({"message": "✅ تم تغيير كلمة السر، جاري تحويلك..."})


@app.route("/admin/api/check-updates")
@requires_auth
def check_updates():
    import urllib.request
    try:
        current = yt_dlp.version.__version__
    except Exception:
        current = "غير معروف"
    try:
        with urllib.request.urlopen("https://pypi.org/pypi/yt-dlp/json", timeout=6) as resp:
            latest = json.loads(resp.read())["info"]["version"]
        def ver_tuple(v):
            try: return tuple(int(x) for x in v.split('.'))
            except: return (0,)
        needs_update = ver_tuple(latest) > ver_tuple(current)
        return jsonify({"current": current, "latest": latest, "needs_update": needs_update})
    except Exception:
        return jsonify({"current": current, "latest": None, "needs_update": False})


@app.route("/admin/api/update-ytdlp", methods=["POST"])
@requires_auth
def admin_update_ytdlp():
    def do_update():
        try:
            subprocess.run(
                ["pip", "install", YTDLP_CONSTRAINT, "--break-system-packages"],
                capture_output=True, text=True, timeout=120
            )
            stats["ytdlp_updated"] = now().strftime("%Y-%m-%d %H:%M")
        except Exception:
            pass
    threading.Thread(target=do_update, daemon=True).start()
    return jsonify({"message": f"جاري تثبيت {YTDLP_CONSTRAINT}..."})


@app.route("/admin/api/clear-files", methods=["POST"])
@requires_auth
def admin_clear_files():
    count = 0
    for f in DOWNLOAD_DIR.iterdir():
        if f.is_file():
            try:
                f.unlink()
                count += 1
            except Exception:
                pass
    return jsonify({"message": f"تم حذف {count} ملف"})


@app.route("/admin/api/generate-code", methods=["POST"])
@requires_auth
def generate_code():
    import secrets
    data = request.get_json() or {}
    email = data.get("email", data.get("note", "")).strip()[:100]
    days = int(data.get("days", 30))

    raw = secrets.token_hex(4).upper()
    code = f"VIP-{raw[:4]}-{raw[4:]}"

    codes = load_codes()
    codes[code] = {
        "used": False,
        "email": email,
        "days": days,
        "created_at": now().strftime("%Y-%m-%d %H:%M"),
        "used_at": None,
        "expires_at": None,
    }
    save_codes(codes)
    return jsonify({"code": code})


@app.route("/admin/api/render-ping")
@requires_auth
def render_ping():
    idle_sec = int(time.time() - _last_activity)
    uptime_sec = int(time.time() - _server_start)
    sleep_in = max(0, 900 - idle_sec)  # Render sleeps after 15 min (900s)
    return jsonify({
        "ok": True,
        "uptime": uptime_sec,
        "idle": idle_sec,
        "sleep_in": sleep_in,
    })


@app.route("/admin/api/codes")
@requires_auth
def list_codes():
    codes = load_codes()
    current_time = now()
    result = []
    for k, v in sorted(codes.items(), key=lambda x: x[1]["created_at"], reverse=True):
        entry = {"code": k, **v, "email": v.get("email") or v.get("note", "")}
        if v["used"] and v.get("expires_at"):
            try:
                exp = datetime.strptime(v["expires_at"], "%Y-%m-%d %H:%M")
                entry["expired"] = current_time > exp
                entry["days_left"] = max(0, (exp - current_time).days)
            except Exception:
                entry["expired"] = False
                entry["days_left"] = 0
        else:
            entry["expired"] = False
            entry["days_left"] = None
        result.append(entry)
    return jsonify(result)


@app.route("/admin/api/extend-code", methods=["POST"])
@requires_auth
def extend_code():
    data = request.get_json() or {}
    code = data.get("code", "").strip()
    days = int(data.get("days", 30))
    codes = load_codes()
    if code not in codes:
        return jsonify({"error": "الكود غير موجود"}), 404
    entry = codes[code]
    if entry.get("expires_at"):
        try:
            base = datetime.strptime(entry["expires_at"], "%Y-%m-%d %H:%M")
            if base < now():
                base = now()
        except Exception:
            base = now()
    else:
        base = now()
    new_exp = (base + timedelta(days=days)).strftime("%Y-%m-%d %H:%M")
    codes[code]["expires_at"] = new_exp
    codes[code]["used"] = True
    save_codes(codes)
    return jsonify({"message": f"تم التمديد حتى {new_exp}"})


@app.route("/admin/api/delete-code", methods=["POST"])
@requires_auth
def delete_code():
    code = ((request.get_json() or {}).get("code", "")).strip()
    codes = load_codes()
    if code in codes:
        del codes[code]
        save_codes(codes)
        return jsonify({"message": "تم الحذف"})
    return jsonify({"error": "الكود غير موجود"}), 404


@app.route("/admin/api/test-smtp", methods=["POST"])
@requires_auth
def test_smtp():
    if not SMTP_USER or not SMTP_PASS:
        return jsonify({"error": f"المتغيرات غير مضبوطة — SMTP_USER='{SMTP_USER}' SMTP_PASS={'مضبوط' if SMTP_PASS else 'فارغ'}"}), 500
    try:
        token = "TEST-TOKEN"
        reset_url = f"https://www.vip-dl.com/admin/reset?token={token}"
        msg = MIMEMultipart()
        msg["Subject"] = "🔐 اختبار إرسال إيميل نزلها بلس"
        msg["From"] = SMTP_USER
        msg["To"] = ADMIN_EMAIL
        msg.attach(MIMEText(f"هذا إيميل اختبار — الإرسال يعمل بشكل صحيح ✅\n\n{reset_url}", "plain", "utf-8"))
        with smtplib.SMTP_SSL("smtp.gmail.com", 465, timeout=15) as server:
            server.login(SMTP_USER, SMTP_PASS)
            server.send_message(msg)
        return jsonify({"message": f"✅ تم الإرسال بنجاح إلى {ADMIN_EMAIL}"})
    except smtplib.SMTPAuthenticationError:
        return jsonify({"error": "❌ خطأ في المصادقة — تحقق من SMTP_USER و SMTP_PASS"}), 500
    except smtplib.SMTPConnectError:
        return jsonify({"error": "❌ فشل الاتصال بـ Gmail"}), 500
    except Exception as e:
        return jsonify({"error": f"❌ {str(e)}"}), 500


@app.route("/api/redeem-code", methods=["POST"])
@limiter.limit("10 per minute")
def redeem_code():
    code = ((request.get_json() or {}).get("code", "")).strip().upper()
    if not code:
        return jsonify({"error": "أدخل الكود"}), 400

    codes = load_codes()
    if code not in codes:
        return jsonify({"error": "الكود غير صحيح"}), 404
    if codes[code]["used"]:
        return jsonify({"error": "هذا الكود مستخدم مسبقاً"}), 409

    days = codes[code].get("days", 30)
    from datetime import timedelta
    current_time = now()
    expires_at = (current_time + timedelta(days=days)).strftime("%Y-%m-%d %H:%M")

    codes[code]["used"] = True
    codes[code]["used_at"] = current_time.strftime("%Y-%m-%d %H:%M")
    codes[code]["expires_at"] = expires_at
    save_codes(codes)
    return jsonify({
        "message": f"تم تفعيل الاشتراك المميز ✅ صالح لـ {days} يوم",
        "expires_at": expires_at,
    })


@app.route("/api/check-premium", methods=["POST"])
def check_premium():
    code = ((request.get_json() or {}).get("code", "")).strip().upper()
    if not code:
        return jsonify({"valid": False}), 400

    codes = load_codes()
    if code not in codes:
        return jsonify({"valid": False, "reason": "not_found"})

    entry = codes[code]
    if not entry["used"]:
        return jsonify({"valid": False, "reason": "not_used"})

    if entry.get("expires_at"):
        try:
            exp = datetime.strptime(entry["expires_at"], "%Y-%m-%d %H:%M")
            if now() > exp:
                return jsonify({"valid": False, "reason": "expired"})
        except Exception:
            pass

    return jsonify({"valid": True, "expires_at": entry.get("expires_at")})


@app.route("/admin/api/change-password", methods=["POST"])
@requires_auth
def change_password():
    global ADMIN_USER, ADMIN_PASS
    data = request.get_json()
    current = (data or {}).get("current", "")
    new_pass = (data or {}).get("new_pass", "")
    new_user = (data or {}).get("new_user", "").strip()

    if not verify_password(ADMIN_PASS, current):
        return jsonify({"error": "كلمة السر الحالية غير صحيحة"}), 401
    if len(new_pass) < 6:
        return jsonify({"error": "كلمة السر الجديدة يجب أن تكون 6 أحرف على الأقل"}), 400

    ADMIN_PASS = generate_password_hash(new_pass)
    if new_user:
        ADMIN_USER = new_user

    cfg = load_config()
    cfg["admin_pass"] = ADMIN_PASS
    cfg["admin_user"] = ADMIN_USER
    save_config(cfg)

    return jsonify({"message": "تم تغيير بيانات الدخول بنجاح ✅"})


# ===== Public API =====
@app.route("/api/info", methods=["POST"])
@limiter.limit("30 per minute")
def get_info():
    data = request.get_json()
    url = (data or {}).get("url", "").strip()

    if not url:
        return jsonify({"error": "الرابط مطلوب"}), 400

    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
        "nocheckcertificate": True,
    }

    apply_platform_opts(url, ydl_opts)

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)

        formats = []
        seen = set()

        for f in (info.get("formats") or []):
            height = f.get("height")
            ext = f.get("ext", "")
            vcodec = f.get("vcodec", "none")
            acodec = f.get("acodec", "none")

            if vcodec == "none" and acodec == "none":
                continue
            if ext not in ("mp4", "webm", "m4a", "mp3"):
                continue

            if vcodec != "none" and height:
                label = f"{height}p"
                ftype = "video"
            elif vcodec == "none" and acodec != "none":
                label = "صوت فقط"
                ftype = "audio"
            else:
                continue

            key = (label, ftype)
            if key in seen:
                continue
            seen.add(key)

            has_both = f.get("vcodec", "none") != "none" and f.get("acodec", "none") != "none"
            formats.append({
                "format_id": f["format_id"],
                "label": label,
                "ext": ext,
                "type": ftype,
                "filesize": f.get("filesize") or f.get("filesize_approx"),
                "direct_url": f.get("url") if has_both else None,
            })

        formats.sort(
            key=lambda x: (
                0 if x["type"] == "video" else 1,
                -int(x["label"].replace("p", "")) if x["label"].endswith("p") else 0,
            )
        )

        has_best = any(f["label"] == "أفضل جودة" for f in formats)
        if not has_best and formats:
            formats.insert(0, {
                "format_id": "best[ext=mp4]/bestvideo+bestaudio/best",
                "label": "أفضل جودة",
                "ext": "mp4",
                "type": "video",
                "filesize": None,
            })

        cache_id = str(uuid.uuid4())
        info_cache[cache_id] = {"info": info, "expires": time.time() + 600}

        return jsonify({
            "title": info.get("title", "فيديو"),
            "thumbnail": info.get("thumbnail") or next((t.get("url") for t in reversed(info.get("thumbnails") or []) if t.get("url")), None),
            "duration": info.get("duration"),
            "uploader": info.get("uploader") or info.get("channel"),
            "platform": info.get("extractor_key", ""),
            "formats": formats,
            "cache_id": cache_id,
        })

    except yt_dlp.utils.DownloadError as e:
        msg = str(e)
        if "Unsupported URL" in msg:
            return jsonify({"error": "هذا الموقع غير مدعوم حالياً"}), 422
        if "Private video" in msg or "login" in msg.lower():
            return jsonify({"error": "الفيديو خاص أو يتطلب تسجيل دخول"}), 403
        return jsonify({"error": "تعذّر جلب معلومات الفيديو، تحقق من الرابط"}), 400
    except Exception:
        return jsonify({"error": "حدث خطأ غير متوقع"}), 500


@app.route("/api/thumb")
@limiter.limit("60 per minute")
def proxy_thumbnail():
    url = request.args.get("url", "").strip()
    if not url or not url.startswith("http"):
        return "", 400
    try:
        headers = {
            "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
            "Referer": "https://www.instagram.com/",
            "Accept": "image/webp,image/avif,image/*,*/*;q=0.8",
        }
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=8) as r:
            data = r.read()
            content_type = r.headers.get("Content-Type", "image/jpeg")
        resp = Response(data, content_type=content_type)
        resp.headers["Cache-Control"] = "public, max-age=3600"
        return resp
    except Exception:
        return "", 502


@app.route("/api/direct-url", methods=["POST"])
@limiter.limit("20 per minute")
def get_direct_url():
    data = request.get_json()
    url = (data or {}).get("url", "").strip()
    if not url:
        return jsonify({"has_direct": False}), 400

    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
        "nocheckcertificate": True,
        "format": "best[ext=mp4]/best",
        "socket_timeout": 15,
    }

    apply_platform_opts(url, ydl_opts)

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)

        if not info:
            return jsonify({"has_direct": False})

        title = info.get("title", "video")
        safe_title = re.sub(r'[\\/*?:"<>|]', "", title)[:60]
        direct_url = None
        ext = "mp4"

        # Single format result (pre-muxed)
        if info.get("url") and info.get("vcodec", "none") != "none" and info.get("acodec", "none") != "none":
            direct_url = info["url"]
            ext = info.get("ext", "mp4")
        elif info.get("formats"):
            for f in reversed(info["formats"]):
                if (f.get("vcodec", "none") != "none" and
                        f.get("acodec", "none") != "none" and
                        f.get("ext") == "mp4" and
                        f.get("url")):
                    direct_url = f["url"]
                    ext = f.get("ext", "mp4")
                    break
            if not direct_url:
                for f in reversed(info["formats"]):
                    if (f.get("vcodec", "none") != "none" and
                            f.get("acodec", "none") != "none" and
                            f.get("url")):
                        direct_url = f["url"]
                        ext = f.get("ext", "mp4")
                        break

        if not direct_url:
            return jsonify({"has_direct": False})

        referer = "https://www.tiktok.com/" if "tiktok" in url.lower() else \
                  "https://www.instagram.com/" if "instagram" in url.lower() else \
                  "https://www.facebook.com/" if "facebook" in url.lower() else url

        return jsonify({
            "has_direct": True,
            "url": direct_url,
            "filename": safe_title + "." + ext,
            "referer": referer,
        })
    except Exception:
        return jsonify({"has_direct": False})


@app.route("/api/download", methods=["POST"])
@limiter.limit("10 per minute")
def start_download():
    data = request.get_json()
    url = (data or {}).get("url", "").strip()
    format_id = (data or {}).get("format_id", "bestvideo+bestaudio/best")
    cache_id = (data or {}).get("cache_id", "")

    if not url:
        return jsonify({"error": "الرابط مطلوب"}), 400

    task_id = str(uuid.uuid4())
    platform = detect_platform(url)
    progress_store[task_id] = {"status": "starting", "percent": 0}
    cached = info_cache.pop(cache_id, None) if cache_id else None

    # Check available disk space before starting (need at least 300 MB)
    import shutil
    try:
        free_mb = shutil.disk_usage(DOWNLOAD_DIR).free // (1024 * 1024)
        if free_mb < 300:
            return jsonify({"error": "السيرفر ممتلئ مؤقتاً، حاول بعد دقيقة"}), 503
    except Exception:
        pass

    def do_download():
        _start = time.time()
        output_path = str(DOWNLOAD_DIR / f"{task_id}.%(ext)s")
        ydl_opts = {
            "format": format_id,
            "outtmpl": output_path,
            "merge_output_format": "mp4",
            "quiet": False,
            "no_warnings": False,
            "noplaylist": True,
            "nocheckcertificate": True,
            "prefer_ffmpeg": True,
            "socket_timeout": 30,
            "retries": 5,
            "fragment_retries": 5,
            "http_chunk_size": 10485760,
            "progress_hooks": [make_progress_hook(task_id)],
            "postprocessors": [
                {"key": "FFmpegVideoRemuxer", "preferedformat": "mp4"},
                {"key": "FFmpegMetadata", "add_metadata": True},
            ],
        }

        if format_id in ("bestaudio", "bestaudio/best"):
            ydl_opts["format"] = "bestaudio/best"
            ydl_opts["postprocessors"] = [{
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": "320",
            }]

        apply_platform_opts(url, ydl_opts)
        print(f"[DOWNLOAD] platform={platform} url={url[:80]} format={ydl_opts.get('format')}", flush=True)

        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                if cached:
                    info = ydl.process_ie_result(cached["info"], download=True)
                else:
                    info = ydl.extract_info(url, download=True)
                title = (info or {}).get("title", "video") if info else "video"
                safe_title = re.sub(r'[\\/*?:"<>|]', "", title)[:60]

            found = list(DOWNLOAD_DIR.glob(f"{task_id}.*"))
            if found:
                ext = found[0].suffix.lower()
                valid_exts = {".mp4", ".webm", ".mkv", ".m4a", ".mp3", ".mov", ".avi", ".flv", ".ts"}
                if ext not in valid_exts:
                    print(f"[DOWNLOAD ERROR] bad ext={ext} platform={platform} url={url[:80]}", flush=True)
                    try: found[0].unlink()
                    except Exception: pass
                    progress_store[task_id] = {"status": "error", "error": "فشل التحميل من المنصة — الفيديو غير متاح أو محمي، جرب رابطاً آخر"}
                    record_download(platform, False, f"bad file ext: {ext}", duration=time.time()-_start)
                else:
                    print(f"[DOWNLOAD OK] platform={platform} ext={ext} title={safe_title[:40]}", flush=True)
                    progress_store[task_id] = {
                        "status": "done",
                        "percent": 100,
                        "file": task_id + ext,
                        "filename": safe_title + ext,
                    }
                    record_download(platform, True, duration=time.time()-_start)
            else:
                progress_store[task_id] = {"status": "error", "error": "الملف لم يُوجد"}
                record_download(platform, False, "الملف لم يُوجد", duration=time.time()-_start)
        except Exception as e:
            err = str(e)[:200]
            err_lower = err.lower()
            if "no space left" in err_lower or "disk" in err_lower:
                friendly = "السيرفر ممتلئ مؤقتاً، حاول بعد دقيقة"
            elif "timed out" in err_lower or "timeout" in err_lower or "socket" in err_lower:
                friendly = "انتهت مهلة التحميل، الفيديو كبير جداً أو الاتصال بطيء — حاول مرة أخرى"
            elif "fragment" in err_lower:
                friendly = "فشل تحميل أجزاء الفيديو، حاول بجودة أقل"
            elif "memory" in err_lower:
                friendly = "الفيديو كبير جداً على السيرفر، حاول بجودة أقل"
            elif "confirm your age" in err_lower or "age-restricted" in err_lower:
                friendly = "هذا الفيديو مقيّد بعمر ولا يمكن تحميله"
            elif "429" in err or "too many" in err_lower or "rate limit" in err_lower:
                friendly = "المنصة تمنع التحميل مؤقتاً بسبب الضغط — حاول بعد دقيقة"
            elif "sign in" in err_lower or "log in" in err_lower or "login required" in err_lower:
                friendly = "الفيديو يتطلب تسجيل دخول، جرب فيديو عاماً آخر"
            elif "private" in err_lower or "not available" in err_lower:
                friendly = "الفيديو غير متاح أو خاص"
            elif "403" in err or "forbidden" in err_lower:
                friendly = "المنصة ترفض التحميل — حاول مرة أخرى بعد دقيقة"
            elif "404" in err or "not found" in err_lower:
                friendly = "الفيديو غير موجود أو تم حذفه"
            else:
                friendly = err
            print(f"[DOWNLOAD FAIL] platform={platform} err={err[:120]}", flush=True)
            progress_store[task_id] = {"status": "error", "error": friendly}
            record_download(platform, False, err, duration=time.time()-_start)

    threading.Thread(target=do_download, daemon=True).start()
    return jsonify({"task_id": task_id})


@app.route("/api/progress/<task_id>")
def get_progress(task_id):
    return jsonify(progress_store.get(task_id, {"status": "not_found"}))


@app.route("/api/file/<filename>")
def serve_file(filename):
    name = Path(filename).stem
    try:
        uuid.UUID(name)
    except ValueError:
        return jsonify({"error": "غير مسموح"}), 403

    filepath = DOWNLOAD_DIR / filename
    if not filepath.exists():
        return jsonify({"error": "الملف غير موجود أو انتهت صلاحيته"}), 404

    download_name = request.args.get("name", filename)
    resp = send_file(
        filepath,
        as_attachment=True,
        download_name=download_name,
        mimetype="video/mp4",
        conditional=True,
    )
    resp.headers["Cache-Control"] = "no-store"
    resp.headers["Content-Encoding"] = "identity"
    return resp


@app.route("/admin/api/download-trend")
@requires_auth
def admin_download_trend():
    days = int(request.args.get("days", 7))
    daily = load_daily_stats()
    result = []
    for i in range(days - 1, -1, -1):
        date = (now() - timedelta(days=i)).date().isoformat()
        result.append({"date": date, "count": daily.get(date, 0)})
    return jsonify(result)


@app.route("/admin/api/hourly-stats")
@requires_auth
def admin_hourly_stats():
    hourly = load_hourly_stats()
    result = [{"hour": h, "count": hourly.get(str(h), 0)} for h in range(24)]
    return jsonify(result)


@app.route("/admin/api/recent-downloads")
@requires_auth
def admin_recent_downloads():
    return jsonify(load_download_log())


@app.route("/admin/api/subscriber-list")
@requires_auth
def admin_subscriber_list():
    codes = load_codes()
    current_time = now()
    result = []
    for code, v in codes.items():
        if not v.get("used"):
            continue
        days_left = None
        expired = False
        if v.get("expires_at"):
            try:
                exp = datetime.strptime(v["expires_at"], "%Y-%m-%d %H:%M")
                days_left = max(0, (exp - current_time).days)
                expired = current_time > exp
            except Exception:
                pass
        if not expired:
            result.append({
                "code": code,
                "note": v.get("note", ""),
                "days_left": days_left,
                "expires_at": v.get("expires_at", ""),
            })
    result.sort(key=lambda x: x["days_left"] if x["days_left"] is not None else 9999)
    return jsonify(result)


@app.route("/admin/api/visitor-device-stats")
@requires_auth
def admin_visitor_device_stats():
    visitors = load_visitors()
    mobile = sum(v.get("mobile", 0) for v in visitors.values())
    desktop = sum(v.get("desktop", 0) for v in visitors.values())
    new_v = sum(v.get("new", 0) for v in visitors.values())
    returning = sum(v.get("returning", 0) for v in visitors.values())
    hourly = load_hourly_stats()
    peak = max(range(24), key=lambda h: hourly.get(str(h), 0))
    return jsonify({"mobile": mobile, "desktop": desktop, "new": new_v, "returning": returning, "peak_hour": peak})


@app.route("/admin/api/test-url", methods=["POST"])
@requires_auth
def admin_test_url():
    url = (request.get_json() or {}).get("url", "").strip()
    if not url:
        return jsonify({"error": "أدخل رابطاً"}), 400
    try:
        with yt_dlp.YoutubeDL({"quiet": True, "no_warnings": True, "noplaylist": True}) as ydl:
            info = ydl.extract_info(url, download=False)
        mins = int((info.get("duration") or 0) // 60)
        secs = int((info.get("duration") or 0) % 60)
        return jsonify({
            "ok": True,
            "title": info.get("title", "—")[:80],
            "platform": info.get("extractor_key", "—"),
            "duration": f"{mins}:{secs:02d}" if info.get("duration") else "—",
            "formats": len(info.get("formats") or []),
        })
    except Exception as e:
        return jsonify({"ok": False, "error": str(e)[:200]}), 422


@app.route("/admin/api/settings", methods=["GET", "POST"])
@requires_auth
def admin_settings_api():
    if request.method == "GET":
        return jsonify(load_settings())
    data = request.get_json() or {}
    s = load_settings()
    for k, v in data.items():
        if k in DEFAULT_SETTINGS:
            s[k] = v
    save_settings(s)
    return jsonify({"message": "تم الحفظ بنجاح"})


@app.route("/admin/api/backup-codes")
@requires_auth
def admin_backup_codes():
    codes = load_codes()
    from flask import make_response
    resp = make_response(json.dumps(codes, ensure_ascii=False, indent=2))
    resp.headers["Content-Type"] = "application/json"
    resp.headers["Content-Disposition"] = "attachment; filename=vip-codes-backup.json"
    return resp


@app.route("/admin/api/reset-visitors", methods=["POST"])
@requires_auth
def admin_reset_visitors():
    VISITORS_FILE.write_text("{}")
    return jsonify({"ok": True, "message": "تم مسح بيانات الزوار"})


@app.errorhandler(404)
def page_not_found(e):
    return render_template("404.html"), 404


@app.errorhandler(500)
def internal_error(e):
    return render_template("404.html", error_code=500), 500


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    app.run(debug=False, host="0.0.0.0", port=port)
