#!/usr/bin/env python3
"""
Family Safety Bot - يعمل على هاتف الطفل عبر Termux
التحكم عبر Telegram من قِبَل ولي الأمر
"""

import os
import json
import subprocess
import threading
import time
import logging
from datetime import datetime

from telegram import Update, InlineKeyboardButton, InlineKeyboardMarkup
from telegram.ext import (
    Application,
    CommandHandler,
    CallbackQueryHandler,
    ContextTypes,
)

# ─── إعدادات ───────────────────────────────────────────────────────────────
TOKEN        = os.environ.get("BOT_TOKEN", "")          # توكن البوت من @BotFather
PARENT_ID    = int(os.environ.get("PARENT_CHAT_ID", "0"))  # معرّف حساب ولي الأمر
DEVICE_NAME  = os.environ.get("DEVICE_NAME", "هاتف الطفل")

logging.basicConfig(
    format="%(asctime)s [%(levelname)s] %(message)s",
    level=logging.INFO,
    handlers=[
        logging.FileHandler("family_bot.log"),
        logging.StreamHandler(),
    ],
)
log = logging.getLogger(__name__)

# ─── حالة البث ──────────────────────────────────────────────────────────────
stream_active = False
stream_thread = None


# ─── دوال مساعدة ─────────────────────────────────────────────────────────────
def run_termux(cmd: list[str], timeout: int = 15) -> dict:
    """تشغيل أمر termux-api وإرجاع JSON."""
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        if result.stdout.strip():
            return json.loads(result.stdout)
        return {}
    except (subprocess.TimeoutExpired, json.JSONDecodeError, FileNotFoundError) as e:
        log.warning("termux command failed: %s — %s", cmd, e)
        return {}


def is_authorized(update: Update) -> bool:
    return update.effective_user.id == PARENT_ID


def auth_required(func):
    async def wrapper(update: Update, context: ContextTypes.DEFAULT_TYPE):
        if not is_authorized(update):
            await update.message.reply_text("⛔ غير مصرح.")
            return
        await func(update, context)
    wrapper.__name__ = func.__name__
    return wrapper


# ─── الأوامر ──────────────────────────────────────────────────────────────────
@auth_required
async def cmd_start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    keyboard = [
        [
            InlineKeyboardButton("📍 الموقع", callback_data="location"),
            InlineKeyboardButton("🔋 البطارية", callback_data="battery"),
        ],
        [
            InlineKeyboardButton("📊 الحالة", callback_data="status"),
            InlineKeyboardButton("📷 صورة", callback_data="photo"),
        ],
        [
            InlineKeyboardButton("🎥 بدء البث", callback_data="stream_start"),
            InlineKeyboardButton("⏹ إيقاف البث", callback_data="stream_stop"),
        ],
    ]
    await update.message.reply_text(
        f"🛡️ *{DEVICE_NAME}* — لوحة التحكم\n\nاختر أمراً:",
        parse_mode="Markdown",
        reply_markup=InlineKeyboardMarkup(keyboard),
    )


@auth_required
async def cmd_location(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await _send_location(update.message.reply_text, context.bot, update.effective_chat.id)


@auth_required
async def cmd_battery(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await _send_battery(update.message.reply_text)


@auth_required
async def cmd_status(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await _send_status(update.message.reply_text)


@auth_required
async def cmd_photo(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await _send_photo(context.bot, update.effective_chat.id)


@auth_required
async def cmd_stream(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await _start_stream(update.message.reply_text, context.bot, update.effective_chat.id)


@auth_required
async def cmd_stop(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await _stop_stream(update.message.reply_text)


# ─── Callback buttons ─────────────────────────────────────────────────────────
async def on_button(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    if query.from_user.id != PARENT_ID:
        await query.answer("⛔ غير مصرح.", show_alert=True)
        return

    await query.answer()
    send = query.message.reply_text
    chat_id = query.message.chat_id

    actions = {
        "location":     lambda: _send_location(send, context.bot, chat_id),
        "battery":      lambda: _send_battery(send),
        "status":       lambda: _send_status(send),
        "photo":        lambda: _send_photo(context.bot, chat_id),
        "stream_start": lambda: _start_stream(send, context.bot, chat_id),
        "stream_stop":  lambda: _stop_stream(send),
    }

    handler = actions.get(query.data)
    if handler:
        await handler()


# ─── منطق الميزات ─────────────────────────────────────────────────────────────
async def _send_location(reply_fn, bot, chat_id: int):
    await reply_fn("⏳ جاري تحديد الموقع...")
    data = run_termux(["termux-location", "-p", "gps", "-r", "once"], timeout=30)

    if not data or "latitude" not in data:
        # محاولة بشبكة الجوال
        data = run_termux(["termux-location", "-p", "network", "-r", "once"], timeout=20)

    if data and "latitude" in data:
        lat = data["latitude"]
        lon = data["longitude"]
        acc = data.get("accuracy", "?")
        maps_url = f"https://www.google.com/maps?q={lat},{lon}"
        text = (
            f"📍 *موقع {DEVICE_NAME}*\n\n"
            f"🌐 خط العرض: `{lat:.6f}`\n"
            f"🌐 خط الطول: `{lon:.6f}`\n"
            f"🎯 الدقة: {acc} متر\n"
            f"🕐 الوقت: {datetime.now().strftime('%H:%M:%S')}\n\n"
            f"[📌 فتح في خرائط Google]({maps_url})"
        )
        await bot.send_location(chat_id, latitude=lat, longitude=lon)
        await bot.send_message(chat_id, text, parse_mode="Markdown", disable_web_page_preview=False)
    else:
        await reply_fn("❌ تعذّر تحديد الموقع. تأكد من تفعيل GPS وإذن الموقع لـ Termux.")


async def _send_battery(reply_fn):
    data = run_termux(["termux-battery-status"])
    if data:
        pct     = data.get("percentage", "?")
        status  = data.get("status", "?")
        plugged = data.get("plugged", "?")

        icon = "🔋" if int(pct or 0) > 20 else "🪫"
        charge_icon = "⚡" if status == "CHARGING" else "🔌" if plugged != "UNPLUGGED" else ""

        status_ar = {
            "CHARGING": "يشحن",
            "DISCHARGING": "يصرف",
            "FULL": "مكتمل",
            "NOT_CHARGING": "لا يشحن",
        }.get(status, status)

        await reply_fn(
            f"{icon} *البطارية — {DEVICE_NAME}*\n\n"
            f"نسبة الشحن: *{pct}%* {charge_icon}\n"
            f"الحالة: {status_ar}\n"
            f"🕐 {datetime.now().strftime('%H:%M:%S')}",
            parse_mode="Markdown",
        )
    else:
        await reply_fn("❌ تعذّر قراءة البطارية.")


async def _send_status(reply_fn):
    wifi   = run_termux(["termux-wifi-connectioninfo"])
    net    = run_termux(["termux-telephony-cellinfo"])
    device = run_termux(["termux-telephony-deviceinfo"])
    batt   = run_termux(["termux-battery-status"])

    wifi_name = wifi.get("ssid", "غير متصل") if wifi else "غير متصل"
    signal    = wifi.get("rssi", "?") if wifi else "?"
    carrier   = device.get("network_operator_name", "?") if device else "?"
    batt_pct  = batt.get("percentage", "?") if batt else "?"

    await reply_fn(
        f"📊 *حالة {DEVICE_NAME}*\n\n"
        f"📶 WiFi: `{wifi_name}` (إشارة: {signal} dBm)\n"
        f"📡 الشبكة: {carrier}\n"
        f"🔋 البطارية: {batt_pct}%\n"
        f"🕐 {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        parse_mode="Markdown",
    )


async def _send_photo(bot, chat_id: int, camera: int = 1):
    """التقاط صورة وإرسالها (camera=1 للكاميرا الأمامية، 0 للخلفية)."""
    path = "/tmp/family_photo.jpg"
    try:
        result = subprocess.run(
            ["termux-camera-photo", "-c", str(camera), path],
            timeout=20, capture_output=True,
        )
        if os.path.exists(path) and os.path.getsize(path) > 0:
            with open(path, "rb") as f:
                await bot.send_photo(
                    chat_id, f,
                    caption=f"📷 {DEVICE_NAME} — {datetime.now().strftime('%H:%M:%S')}",
                )
            os.remove(path)
        else:
            await bot.send_message(chat_id, "❌ تعذّر التقاط الصورة. تأكد من إذن الكاميرا.")
    except subprocess.TimeoutExpired:
        await bot.send_message(chat_id, "⏳ انتهت مهلة الكاميرا.")


async def _start_stream(reply_fn, bot, chat_id: int):
    global stream_active, stream_thread

    if stream_active:
        await reply_fn("🎥 البث نشط بالفعل.")
        return

    stream_active = True
    await reply_fn("🎥 بدأ البث — صورة كل 10 ثوانٍ. أرسل /stop للإيقاف.")

    def stream_loop():
        global stream_active
        import asyncio

        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)

        while stream_active:
            try:
                loop.run_until_complete(_send_photo(bot, chat_id))
            except Exception as e:
                log.error("stream error: %s", e)
            for _ in range(10):
                if not stream_active:
                    break
                time.sleep(1)

        loop.close()

    stream_thread = threading.Thread(target=stream_loop, daemon=True)
    stream_thread.start()


async def _stop_stream(reply_fn):
    global stream_active
    if stream_active:
        stream_active = False
        await reply_fn("⏹ تم إيقاف البث.")
    else:
        await reply_fn("ℹ️ لا يوجد بث نشط.")


# ─── تشغيل البوت ──────────────────────────────────────────────────────────────
def main():
    if not TOKEN or not PARENT_ID:
        print("❌ يجب تعيين BOT_TOKEN و PARENT_CHAT_ID في ملف .env")
        return

    app = Application.builder().token(TOKEN).build()

    app.add_handler(CommandHandler("start",    cmd_start))
    app.add_handler(CommandHandler("location", cmd_location))
    app.add_handler(CommandHandler("battery",  cmd_battery))
    app.add_handler(CommandHandler("status",   cmd_status))
    app.add_handler(CommandHandler("photo",    cmd_photo))
    app.add_handler(CommandHandler("stream",   cmd_stream))
    app.add_handler(CommandHandler("stop",     cmd_stop))
    app.add_handler(CallbackQueryHandler(on_button))

    log.info("✅ البوت يعمل — %s", DEVICE_NAME)
    app.run_polling(drop_pending_updates=True)


if __name__ == "__main__":
    main()
