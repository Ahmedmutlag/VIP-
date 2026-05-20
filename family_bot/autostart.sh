#!/data/data/com.termux/files/usr/bin/bash
# ضعه في ~/.termux/boot/start_bot.sh ليعمل تلقائياً عند تشغيل الهاتف
# يتطلب تطبيق Termux:Boot من F-Droid

sleep 10  # انتظر حتى يتحمل الشبكة

SCRIPT_DIR="/data/data/com.termux/files/home/family_bot"
cd "$SCRIPT_DIR"

if [ -f .env ]; then
    export $(grep -v '^#' .env | xargs)
fi

nohup python bot.py >> family_bot.log 2>&1 &
