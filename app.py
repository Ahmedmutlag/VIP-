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
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart
from pathlib import Path
from datetime import datetime, timedelta
from flask import Flask, request, jsonify, send_file, render_template, Response, session, redirect, url_for
from flask_cors import CORS
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address
import yt_dlp


# ===== Auto-update yt-dlp =====
def auto_update_ytdlp():
    try:
        subprocess.run(
            ["pip", "install", "-U", "yt-dlp", "--quiet", "--break-system-packages"],
            timeout=120, check=False
        )
        stats["ytdlp_updated"] = datetime.now().strftime("%Y-%m-%d %H:%M")
    except Exception:
        pass


threading.Thread(target=auto_update_ytdlp, daemon=True).start()

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY", "vip-secret-2026-xk9z")
CORS(app)

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
    if locked_until and datetime.now() < locked_until:
        mins = max(1, int((locked_until - datetime.now()).total_seconds() // 60) + 1)
        return True, mins
    return False, 0

def record_failed_login(ip):
    if ip not in login_attempts:
        login_attempts[ip] = {"count": 0, "locked_until": None}
    login_attempts[ip]["count"] += 1
    if login_attempts[ip]["count"] >= 5:
        login_attempts[ip]["locked_until"] = datetime.now() + timedelta(minutes=15)
        login_attempts[ip]["count"] = 0

def clear_login_attempts(ip):
    login_attempts.pop(ip, None)

DOWNLOAD_DIR = Path("downloads")
DOWNLOAD_DIR.mkdir(exist_ok=True)

progress_store = {}

STRIPE_PAYMENT_LINK = os.environ.get("STRIPE_PAYMENT_LINK", "#pricing")
ADMIN_USER = os.environ.get("ADMIN_USER", "admin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "vip2026")
ADMIN_EMAIL = os.environ.get("ADMIN_EMAIL", "ahmed.alabdan2@gmail.com")
SMTP_USER = os.environ.get("SMTP_USER", "")
SMTP_PASS = os.environ.get("SMTP_PASS", "")
RESET_SECRET = os.environ.get("RESET_SECRET", "")
SMTP_PASS = os.environ.get("SMTP_PASS", "")

reset_tokens = {}  # token -> {"expires": datetime}

CONFIG_FILE  = Path("data/config.json")
CODES_FILE   = Path("data/codes.json")
RATINGS_FILE = Path("data/ratings.json")
STATS_FILE   = Path("data/stats.json")
CONFIG_FILE.parent.mkdir(exist_ok=True)

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

def load_codes():
    if CODES_FILE.exists():
        try:
            return json.loads(CODES_FILE.read_text())
        except Exception:
            pass
    return {}

def save_codes(data):
    CODES_FILE.write_text(json.dumps(data, ensure_ascii=False))

config = load_config()
if "admin_pass" in config:
    ADMIN_PASS = config["admin_pass"]
if "admin_user" in config:
    ADMIN_USER = config["admin_user"]

SERVER_START = datetime.now()

# ===== Live Stats =====
_saved = load_stats_file()
stats = {
    "total_downloads": _saved.get("total_downloads", 0),
    "today_downloads": 0,
    "failed_downloads": _saved.get("failed_downloads", 0),
    "platform_counts": _saved.get("platform_counts", {"TikTok": 0, "Instagram": 0, "Facebook": 0, "Pinterest": 0, "Other": 0}),
    "recent_errors": [],
    "ytdlp_updated": "لم يتم بعد",
    "last_reset_date": datetime.now().date().isoformat(),
}

stats_lock = threading.Lock()


def record_download(platform, success, error_msg=""):
    with stats_lock:
        today = datetime.now().date().isoformat()
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
                    "time": datetime.now().strftime("%H:%M:%S"),
                    "error": error_msg[:120],
                    "platform": platform,
                })
                stats["recent_errors"] = stats["recent_errors"][:10]

        save_stats_file({
            "total_downloads": stats["total_downloads"],
            "failed_downloads": stats["failed_downloads"],
            "platform_counts": stats["platform_counts"],
        })


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
def check_auth(username, password):
    return username == ADMIN_USER and password == ADMIN_PASS


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
            if f.is_file() and (now - f.stat().st_mtime) > 1800:
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
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
  <rect width="512" height="512" rx="100" fill="#7c3aed"/>
  <text x="256" y="300" font-size="260" text-anchor="middle" font-family="Arial">⬇️</text>
  <text x="256" y="420" font-size="72" text-anchor="middle" font-family="Arial" font-weight="bold" fill="white">VIP</text>
</svg>'''
    from flask import make_response
    resp = make_response(svg)
    resp.headers['Content-Type'] = 'image/svg+xml'
    return resp


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
    return render_template("index.html", stripe_link=STRIPE_PAYMENT_LINK)


# ===== Admin Dashboard =====
@app.route("/admin")
@requires_auth
def admin():
    return render_template("admin.html")


@app.route("/admin/api/stats")
@requires_auth
def admin_stats():
    uptime_sec = int((datetime.now() - SERVER_START).total_seconds())
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
        "server_time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
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
<title>تسجيل دخول — VIP Admin</title>
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
  <div class="logo">⚙️ لوحة تحكم <span>VIP</span></div>
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
        ADMIN_PASS = new_pass
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

طُلب إعادة تعيين كلمة سر لوحة تحكم VIP Downloader.

اضغط على الرابط التالي لإعادة تعيين كلمة السر:
{reset_url}

الرابط صالح لمدة 30 دقيقة فقط.
إذا لم تطلب ذلك، تجاهل هذا الإيميل.

— VIP Downloader
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
    reset_tokens[token] = {"expires": datetime.now() + timedelta(minutes=30)}

    try:
        send_reset_email(token)
        return jsonify({"message": "✅ تم إرسال الرابط إلى إيميلك"})
    except Exception as e:
        del reset_tokens[token]
        return jsonify({"error": "فشل إرسال الإيميل، تحقق من إعدادات SMTP"}), 500


@app.route("/admin/reset")
def admin_reset_page():
    token = request.args.get("token", "")
    valid = token in reset_tokens and datetime.now() < reset_tokens[token]["expires"]
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

    if token not in reset_tokens or datetime.now() > reset_tokens[token]["expires"]:
        return jsonify({"error": "الرابط منتهي أو غير صالح"}), 403
    if len(new_pass) < 6:
        return jsonify({"error": "كلمة السر يجب أن تكون 6 أحرف على الأقل"}), 400

    ADMIN_PASS = new_pass
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
        needs_update = latest != current
        return jsonify({"current": current, "latest": latest, "needs_update": needs_update})
    except Exception:
        return jsonify({"current": current, "latest": None, "needs_update": False})


@app.route("/admin/api/update-ytdlp", methods=["POST"])
@requires_auth
def admin_update_ytdlp():
    def do_update():
        try:
            result = subprocess.run(
                ["pip", "install", "-U", "yt-dlp", "--break-system-packages"],
                capture_output=True, text=True, timeout=120
            )
            stats["ytdlp_updated"] = datetime.now().strftime("%Y-%m-%d %H:%M")
        except Exception:
            pass
    threading.Thread(target=do_update, daemon=True).start()
    return jsonify({"message": "جاري التحديث..."})


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
    note = data.get("note", "").strip()[:50]
    days = int(data.get("days", 30))

    raw = secrets.token_hex(4).upper()
    code = f"VIP-{raw[:4]}-{raw[4:]}"

    codes = load_codes()
    codes[code] = {
        "used": False,
        "note": note,
        "days": days,
        "created_at": datetime.now().strftime("%Y-%m-%d %H:%M"),
        "used_at": None,
        "expires_at": None,
    }
    save_codes(codes)
    return jsonify({"code": code})


@app.route("/admin/api/codes")
@requires_auth
def list_codes():
    codes = load_codes()
    now = datetime.now()
    result = []
    for k, v in sorted(codes.items(), key=lambda x: x[1]["created_at"], reverse=True):
        entry = {"code": k, **v}
        if v["used"] and v.get("expires_at"):
            try:
                exp = datetime.strptime(v["expires_at"], "%Y-%m-%d %H:%M")
                entry["expired"] = now > exp
                entry["days_left"] = max(0, (exp - now).days)
            except Exception:
                entry["expired"] = False
                entry["days_left"] = 0
        else:
            entry["expired"] = False
            entry["days_left"] = None
        result.append(entry)
    return jsonify(result)


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
        msg["Subject"] = "🔐 اختبار إرسال إيميل VIP Admin"
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
    now = datetime.now()
    from datetime import timedelta
    expires_at = (now + timedelta(days=days)).strftime("%Y-%m-%d %H:%M")

    codes[code]["used"] = True
    codes[code]["used_at"] = now.strftime("%Y-%m-%d %H:%M")
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
            if datetime.now() > exp:
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

    if current != ADMIN_PASS:
        return jsonify({"error": "كلمة السر الحالية غير صحيحة"}), 401
    if len(new_pass) < 6:
        return jsonify({"error": "كلمة السر الجديدة يجب أن تكون 6 أحرف على الأقل"}), 400

    ADMIN_PASS = new_pass
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

            formats.append({
                "format_id": f["format_id"],
                "label": label,
                "ext": ext,
                "type": ftype,
                "filesize": f.get("filesize") or f.get("filesize_approx"),
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
                "format_id": "bestvideo+bestaudio/best",
                "label": "أفضل جودة",
                "ext": "mp4",
                "type": "video",
                "filesize": None,
            })

        return jsonify({
            "title": info.get("title", "فيديو"),
            "thumbnail": info.get("thumbnail"),
            "duration": info.get("duration"),
            "uploader": info.get("uploader") or info.get("channel"),
            "platform": info.get("extractor_key", ""),
            "formats": formats,
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


@app.route("/api/download", methods=["POST"])
@limiter.limit("10 per minute")
def start_download():
    data = request.get_json()
    url = (data or {}).get("url", "").strip()
    format_id = (data or {}).get("format_id", "bestvideo+bestaudio/best")

    if not url:
        return jsonify({"error": "الرابط مطلوب"}), 400

    task_id = str(uuid.uuid4())
    platform = detect_platform(url)
    progress_store[task_id] = {"status": "starting", "percent": 0}

    def do_download():
        output_path = str(DOWNLOAD_DIR / f"{task_id}.%(ext)s")
        ydl_opts = {
            "format": format_id,
            "outtmpl": output_path,
            "merge_output_format": "mp4",
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
            "nocheckcertificate": True,
            "progress_hooks": [make_progress_hook(task_id)],
            "postprocessors": [{"key": "FFmpegVideoConvertor", "preferedformat": "mp4"}],
        }

        if format_id in ("bestaudio", "bestaudio/best"):
            ydl_opts["format"] = "bestaudio/best"
            ydl_opts["postprocessors"] = [{
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": "192",
            }]

        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(url, download=True)
                title = info.get("title", "video")
                safe_title = re.sub(r'[\\/*?:"<>|]', "", title)[:60]

            found = list(DOWNLOAD_DIR.glob(f"{task_id}.*"))
            if found:
                ext = found[0].suffix
                progress_store[task_id] = {
                    "status": "done",
                    "percent": 100,
                    "file": task_id + ext,
                    "filename": safe_title + ext,
                }
                record_download(platform, True)
            else:
                progress_store[task_id] = {"status": "error", "error": "الملف لم يُوجد"}
                record_download(platform, False, "الملف لم يُوجد")
        except Exception as e:
            err = str(e)[:200]
            progress_store[task_id] = {"status": "error", "error": err}
            record_download(platform, False, err)

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
    return send_file(filepath, as_attachment=True, download_name=download_name)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    app.run(debug=False, host="0.0.0.0", port=port)
