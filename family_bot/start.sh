#!/data/data/com.termux/files/usr/bin/bash
# تشغيل البوت في الخلفية مع حفظ السجل
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# تحميل متغيرات البيئة
if [ -f .env ]; then
    export $(grep -v '^#' .env | xargs)
fi

# تثبيت المكتبات إن لم تكن موجودة
pip install -q -r requirements.txt

# تشغيل في الخلفية
nohup python bot.py >> family_bot.log 2>&1 &
echo $! > bot.pid

echo "✅ البوت يعمل في الخلفية (PID: $(cat bot.pid))"
echo "📄 السجل: $SCRIPT_DIR/family_bot.log"
echo "🛑 للإيقاف: bash stop.sh"
