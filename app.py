import os
import re
import uuid
import threading
import time
from pathlib import Path
from flask import Flask, request, jsonify, send_file, render_template
from flask_cors import CORS
import yt_dlp

app = Flask(__name__)
CORS(app)

DOWNLOAD_DIR = Path("downloads")
DOWNLOAD_DIR.mkdir(exist_ok=True)

progress_store = {}

STRIPE_PAYMENT_LINK = os.environ.get("STRIPE_PAYMENT_LINK", "#pricing")

SUPPORTED_DOMAINS = [
    "instagram.com",
    "tiktok.com",
    "facebook.com", "fb.watch",
    "vm.tiktok.com",
]


def clean_old_files():
    """Delete files older than 30 minutes."""
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


@app.route("/")
def index():
    return render_template("index.html", stripe_link=STRIPE_PAYMENT_LINK)


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

        # Add best quality shortcut if not present
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
    except Exception as e:
        return jsonify({"error": "حدث خطأ غير متوقع"}), 500


@app.route("/api/download", methods=["POST"])
def start_download():
    data = request.get_json()
    url = (data or {}).get("url", "").strip()
    format_id = (data or {}).get("format_id", "bestvideo+bestaudio/best")

    if not url:
        return jsonify({"error": "الرابط مطلوب"}), 400

    task_id = str(uuid.uuid4())
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
            "postprocessors": [{
                "key": "FFmpegVideoConvertor",
                "preferedformat": "mp4",
            }],
        }

        # For audio-only
        if format_id in ("bestaudio", "bestaudio/best"):
            ydl_opts["format"] = "bestaudio/best"
            ydl_opts["nocheckcertificate"] = True
            ydl_opts["postprocessors"] = [{
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": "192",
            }]
            ydl_opts["outtmpl"] = str(DOWNLOAD_DIR / f"{task_id}.%(ext)s")

        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(url, download=True)
                title = info.get("title", "video")
                # Sanitize title for filename
                safe_title = re.sub(r'[\\/*?:"<>|]', "", title)[:60]

            # Find downloaded file
            found = list(DOWNLOAD_DIR.glob(f"{task_id}.*"))
            if found:
                ext = found[0].suffix
                progress_store[task_id] = {
                    "status": "done",
                    "percent": 100,
                    "file": task_id + ext,
                    "filename": safe_title + ext,
                }
            else:
                progress_store[task_id] = {"status": "error", "error": "الملف لم يُوجد"}
        except Exception as e:
            progress_store[task_id] = {"status": "error", "error": str(e)[:200]}

    threading.Thread(target=do_download, daemon=True).start()
    return jsonify({"task_id": task_id})


@app.route("/api/progress/<task_id>")
def get_progress(task_id):
    data = progress_store.get(task_id, {"status": "not_found"})
    return jsonify(data)


@app.route("/api/file/<filename>")
def serve_file(filename):
    # Security: only allow files that match uuid pattern
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
