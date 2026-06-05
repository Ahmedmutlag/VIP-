import os
import time
import base64
import threading
from datetime import datetime
from functools import wraps

from flask import (
    Flask, request, jsonify, render_template, session,
    redirect, url_for, send_from_directory,
)
from flask_socketio import SocketIO, emit

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY", "change-me-abc123")
socketio = SocketIO(app, cors_allowed_origins="*", async_mode="eventlet")

DASHBOARD_PASSWORD = os.environ.get("DASHBOARD_PASSWORD", "family123")
UPLOAD_FOLDER = os.path.join(os.path.dirname(__file__), "uploads")
os.makedirs(UPLOAD_FOLDER, exist_ok=True)

devices = {}
devices_lock = threading.Lock()


def get_device(device_id, device_name="Unknown Device"):
    with devices_lock:
        if device_id not in devices:
            devices[device_id] = {
                "device_id": device_id,
                "device_name": device_name,
                "location": None,
                "photo_path": None,
                "status": None,
                "command": None,
                "tracking": False,
                "streaming": False,
                "active_app": None,
                "screen_time": None,
                "browser_history": None,
                "last_seen": datetime.utcnow().isoformat(),
            }
        else:
            if device_name and device_name != "Unknown Device":
                devices[device_id]["device_name"] = device_name
            devices[device_id]["last_seen"] = datetime.utcnow().isoformat()
        return devices[device_id]


def req_device_id():
    return (
        request.headers.get("X-Device-Id")
        or request.args.get("device_id")
        or request.remote_addr
    )


def req_device_name():
    return request.headers.get("X-Device-Name") or "Unknown Device"


def login_required(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        if not session.get("authenticated"):
            return redirect(url_for("login"))
        return f(*args, **kwargs)
    return decorated


@app.route("/login", methods=["GET", "POST"])
def login():
    error = None
    if request.method == "POST":
        if request.form.get("password", "") == DASHBOARD_PASSWORD:
            session["authenticated"] = True
            return redirect(url_for("dashboard"))
        error = "Incorrect password."
    return render_template("login.html", error=error)


@app.route("/logout")
def logout():
    session.clear()
    return redirect(url_for("login"))


@app.route("/")
@login_required
def dashboard():
    with devices_lock:
        all_devices = list(devices.values())
    return render_template("dashboard.html", devices=all_devices)


@app.route("/uploads/<filename>")
@login_required
def uploaded_file(filename):
    return send_from_directory(UPLOAD_FOLDER, filename)


@app.route("/api/location", methods=["POST"])
def api_location():
    data = request.get_json(silent=True) or {}
    lat, lon = data.get("lat"), data.get("lon")
    if lat is None or lon is None:
        return jsonify({"error": "lat and lon required"}), 400
    did = req_device_id()
    dev = get_device(did, req_device_name())
    location = {
        "lat": float(lat), "lon": float(lon),
        "accuracy": data.get("accuracy"),
        "timestamp": data.get("timestamp") or datetime.utcnow().isoformat(),
    }
    with devices_lock:
        dev["location"] = location
    socketio.emit("location_update", {"device_id": did, **location})
    return jsonify({"status": "ok"})


@app.route("/api/photo", methods=["POST"])
def api_photo():
    if "photo" not in request.files:
        return jsonify({"error": "photo file required"}), 400
    did = req_device_id()
    dev = get_device(did, req_device_name())
    photo = request.files["photo"]
    filename = f"photo_{did}_{int(time.time())}.jpg"
    filepath = os.path.join(UPLOAD_FOLDER, filename)
    photo.save(filepath)
    with devices_lock:
        dev["photo_path"] = filename
    with open(filepath, "rb") as f:
        b64_data = base64.b64encode(f.read()).decode("utf-8")
    socketio.emit("photo_update", {"device_id": did, "filename": filename, "data": b64_data})
    return jsonify({"status": "ok", "filename": filename})


@app.route("/api/frame", methods=["POST"])
def api_frame():
    data = request.get_json(silent=True) or {}
    frame_b64 = data.get("frame")
    if not frame_b64:
        return jsonify({"error": "frame required"}), 400
    did = req_device_id()
    get_device(did, req_device_name())
    socketio.emit("frame_update", {"device_id": did, "frame": frame_b64})
    return jsonify({"status": "ok"})


@app.route("/api/command", methods=["GET"])
def api_command():
    did = req_device_id()
    dev = get_device(did, req_device_name())
    with devices_lock:
        cmd = dev["command"]
        dev["command"] = None
    return jsonify({"command": cmd})


@app.route("/api/status", methods=["POST"])
def api_status():
    data = request.get_json(silent=True) or {}
    did = req_device_id()
    dev = get_device(did, req_device_name())
    status = {
        "battery": data.get("battery"),
        "wifi": data.get("wifi"),
        "timestamp": data.get("timestamp") or datetime.utcnow().isoformat(),
    }
    with devices_lock:
        dev["status"] = status
    socketio.emit("status_update", {"device_id": did, **status})
    return jsonify({"status": "ok"})


@app.route("/api/active-app", methods=["POST"])
def api_active_app():
    data = request.get_json(silent=True) or {}
    did = req_device_id()
    dev = get_device(did, req_device_name())
    app_info = {
        "app_name": data.get("app_name", ""),
        "app_package": data.get("app_package", ""),
        "timestamp": data.get("timestamp") or datetime.utcnow().isoformat(),
    }
    with devices_lock:
        dev["active_app"] = app_info
    socketio.emit("active_app_update", {"device_id": did, **app_info})
    return jsonify({"status": "ok"})


@app.route("/api/screen-time", methods=["POST"])
def api_screen_time():
    data = request.get_json(silent=True) or {}
    did = req_device_id()
    dev = get_device(did, req_device_name())
    screen_time = {
        "apps": data.get("apps", []),
        "timestamp": data.get("timestamp") or datetime.utcnow().isoformat(),
    }
    with devices_lock:
        dev["screen_time"] = screen_time
    socketio.emit("screen_time_update", {"device_id": did, **screen_time})
    return jsonify({"status": "ok"})


@app.route("/api/browser-history", methods=["POST"])
def api_browser_history():
    data = request.get_json(silent=True) or {}
    did = req_device_id()
    dev = get_device(did, req_device_name())
    history = {
        "sites": data.get("sites", []),
        "count": data.get("count", 0),
        "timestamp": data.get("timestamp") or datetime.utcnow().isoformat(),
    }
    with devices_lock:
        dev["browser_history"] = history
    socketio.emit("browser_history_update", {"device_id": did, **history})
    return jsonify({"status": "ok"})


@socketio.on("send_command")
def handle_send_command(data):
    if not session.get("authenticated"):
        return
    cmd = data.get("command")
    did = data.get("device_id")
    valid = ("get_location", "take_photo", "start_tracking", "stop_tracking",
             "get_status", "start_stream", "stop_stream")
    if cmd not in valid or not did:
        return
    with devices_lock:
        if did not in devices:
            return
        dev = devices[did]
        dev["command"] = cmd
        if cmd == "start_tracking":
            dev["tracking"] = True
        elif cmd == "stop_tracking":
            dev["tracking"] = False
        elif cmd == "start_stream":
            dev["streaming"] = True
        elif cmd == "stop_stream":
            dev["streaming"] = False
    emit("command_queued", {"device_id": did, "command": cmd}, broadcast=True)


@socketio.on("request_state")
def handle_request_state():
    if not session.get("authenticated"):
        return
    with devices_lock:
        all_devices = [dict(d) for d in devices.values()]
    emit("devices_state", {"devices": [
        {k: v for k, v in d.items() if k not in ("photo_path",)} for d in all_devices
    ]})
    for dev in all_devices:
        did = dev["device_id"]
        if dev.get("location"):
            emit("location_update", {"device_id": did, **dev["location"]})
        if dev.get("status"):
            emit("status_update", {"device_id": did, **dev["status"]})
        if dev.get("active_app"):
            emit("active_app_update", {"device_id": did, **dev["active_app"]})
        if dev.get("screen_time"):
            emit("screen_time_update", {"device_id": did, **dev["screen_time"]})
        if dev.get("browser_history"):
            emit("browser_history_update", {"device_id": did, **dev["browser_history"]})
        if dev.get("photo_path"):
            fp = os.path.join(UPLOAD_FOLDER, dev["photo_path"])
            if os.path.exists(fp):
                with open(fp, "rb") as f:
                    b64 = base64.b64encode(f.read()).decode("utf-8")
                emit("photo_update", {"device_id": did, "filename": dev["photo_path"], "data": b64})
        emit("tracking_state", {
            "device_id": did,
            "tracking": dev.get("tracking", False),
            "streaming": dev.get("streaming", False),
        })


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    socketio.run(app, host="0.0.0.0", port=port, debug=False)
