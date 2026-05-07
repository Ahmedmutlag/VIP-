import os
import re
import uuid
import threading
import time
import subprocess
import functools
import json
from pathlib import Path
from datetime import datetime
from flask import Flask, request, jsonify, send_file, render_template, Response
from flask_cors import CORS
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
CORS(app)

DOWNLOAD_DIR = Path("downloads")
DOWNLOAD_DIR.mkdir(exist_ok=True)

progress_store = {}

STRIPE_PAYMENT_LINK = os.environ.get("STRIPE_PAYMENT_LINK", "#pricing")
ADMIN_USER = os.environ.get("ADMIN_USER", "admin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "vip2026")

CONFIG_FILE = Path("data/config.json")
CONFIG_FILE.parent.mkdir(exist_ok=True)

def load_config():
    if CONFIG_FILE.exists():
        try:
            return json.loads(CONFIG_FILE.read_text())
        except Exception:
            pass
    return {}

def save_config(data):
    CONFIG_FILE.write_text(json.dumps(data))

config = load_config()
if "admin_pass" in config:
    ADMIN_PASS = config["admin_pass"]
if "admin_user" in config:
    ADMIN_USER = config["admin_user"]

SERVER_START = datetime.now()

# ===== Live Stats =====
stats = {
    "total_downloads": 0,
    "today_downloads": 0,
    "failed_downloads": 0,
    "platform_counts": {"TikTok": 0, "Instagram": 0, "Facebook": 0, "Other": 0},
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


def detect_platform(url):
    url = url.lower()
    if "tiktok.com" in url or "vm.tiktok.com" in url:
        return "TikTok"
    if "instagram.com" in url:
        return "Instagram"
    if "facebook.com" in url or "fb.watch" in url:
        return "Facebook"
    return "Other"


# ===== Admin Auth =====
def check_auth(username, password):
    return username == ADMIN_USER and password == ADMIN_PASS


def requires_auth(f):
    @functools.wraps(f)
    def decorated(*args, **kwargs):
        auth = request.authorization
        if not auth or not check_auth(auth.username, auth.password):
            return Response(
                "يجب تسجيل الدخول للوصول للوحة التحكم",
                401,
                {"WWW-Authenticate": 'Basic realm="VIP Admin"'}
            )
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
