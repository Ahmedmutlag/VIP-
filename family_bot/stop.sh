#!/data/data/com.termux/files/usr/bin/bash
if [ -f bot.pid ]; then
    kill "$(cat bot.pid)" 2>/dev/null && echo "⏹ البوت أُوقف." || echo "⚠️ لم يُعثر على العملية."
    rm -f bot.pid
else
    echo "⚠️ ملف bot.pid غير موجود."
fi
