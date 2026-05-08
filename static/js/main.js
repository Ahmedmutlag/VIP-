// ===== PWA Install =====
let deferredPrompt = null;

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/static/sw.js');
}

window.addEventListener('beforeinstallprompt', e => {
  e.preventDefault();
  deferredPrompt = e;
  // Show banner after 3 seconds if not dismissed before
  if (!localStorage.getItem('pwa_dismissed')) {
    setTimeout(() => {
      document.getElementById('installBanner').classList.remove('hidden');
    }, 3000);
  }
});

window.addEventListener('appinstalled', () => {
  document.getElementById('installBanner').classList.add('hidden');
  deferredPrompt = null;
});

document.getElementById('installBtn').addEventListener('click', async () => {
  if (!deferredPrompt) {
    // iOS fallback instructions
    showIOSInstallHint();
    return;
  }
  deferredPrompt.prompt();
  const { outcome } = await deferredPrompt.userChoice;
  deferredPrompt = null;
  document.getElementById('installBanner').classList.add('hidden');
});

function dismissInstall() {
  document.getElementById('installBanner').classList.add('hidden');
  localStorage.setItem('pwa_dismissed', '1');
}

function showIOSInstallHint() {
  const toast = document.createElement('div');
  toast.style.cssText = `
    position:fixed; bottom:5rem; left:50%; transform:translateX(-50%);
    background:var(--card); border:1px solid var(--accent);
    color:var(--text); padding:1rem 1.5rem; border-radius:14px;
    font-size:.9rem; z-index:9999; text-align:center; width:280px;
    box-shadow:0 8px 30px rgba(0,0,0,.5);
  `;
  toast.innerHTML = `اضغط <strong>مشاركة</strong> ثم <strong>"إضافة إلى الشاشة الرئيسية"</strong>`;
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 5000);
}

// ===== Particles =====
(function createParticles() {
  const container = document.getElementById('particles');
  const colors = ['#7c3aed', '#a855f7', '#06b6d4', '#10b981'];
  for (let i = 0; i < 30; i++) {
    const p = document.createElement('div');
    p.className = 'particle';
    const size = Math.random() * 12 + 4;
    p.style.cssText = `
      width:${size}px; height:${size}px;
      left:${Math.random() * 100}%;
      background:${colors[Math.floor(Math.random() * colors.length)]};
      animation-duration:${Math.random() * 20 + 15}s;
      animation-delay:${Math.random() * -30}s;
    `;
    container.appendChild(p);
  }
})();

// ===== Paste Button =====
document.getElementById('pasteBtn').addEventListener('click', async () => {
  try {
    const text = await navigator.clipboard.readText();
    document.getElementById('urlInput').value = text;
  } catch {
    document.getElementById('urlInput').focus();
  }
});

document.getElementById('urlInput').addEventListener('keydown', e => {
  if (e.key === 'Enter') fetchInfo();
});

// ===== State =====
let currentUrl = '';
let currentFormats = [];
let currentThumbnail = '';
let selectedFormat = null;
let pollInterval = null;
let pendingTaskId = null;
let pendingFilename = null;

function openOriginalVideo() {
  if (currentUrl) window.open(currentUrl, '_blank', 'noopener,noreferrer');
}

// ===== Premium Status =====
function isPremium() {
  if (localStorage.getItem('vip_premium') !== 'true') return false;
  const exp = localStorage.getItem('vip_expires');
  if (exp && Date.now() > parseInt(exp)) {
    localStorage.removeItem('vip_premium');
    localStorage.removeItem('vip_code');
    localStorage.removeItem('vip_expires');
    return false;
  }
  return true;
}

async function verifyPremiumWithServer() {
  const code = localStorage.getItem('vip_code');
  if (!code) return;
  try {
    const res = await fetch('/api/check-premium', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code }),
    });
    const data = await res.json();
    if (data.valid) {
      localStorage.setItem('vip_premium', 'true');
      if (data.expires_at) {
        const ms = new Date(data.expires_at).getTime();
        localStorage.setItem('vip_expires', ms);
      }
    } else {
      localStorage.removeItem('vip_premium');
      localStorage.removeItem('vip_code');
      localStorage.removeItem('vip_expires');
    }
  } catch {}
}

verifyPremiumWithServer();

function friendlyError(raw) {
  if (!raw) return 'حدث خطأ غير متوقع، حاول مرة أخرى';
  const r = raw.toLowerCase();
  if (r.includes('unsupported url') || r.includes('غير مدعوم'))
    return 'هذا الرابط غير مدعوم — تأكد أنه من TikTok أو Instagram أو Facebook أو Pinterest';
  if (r.includes('private') || r.includes('login') || r.includes('خاص'))
    return 'الفيديو خاص أو يتطلب تسجيل دخول — جرّب فيديو عاماً';
  if (r.includes('not found') || r.includes('404') || r.includes('غير موجود'))
    return 'الفيديو غير موجود أو تم حذفه من المنصة';
  if (r.includes('geo') || r.includes('region') || r.includes('country'))
    return 'هذا الفيديو مقيّد في منطقتك الجغرافية';
  if (r.includes('copyright') || r.includes('حقوق'))
    return 'لا يمكن تحميل هذا الفيديو بسبب قيود حقوق النشر';
  if (r.includes('network') || r.includes('connection') || r.includes('timeout') || r.includes('الاتصال'))
    return 'تعذّر الاتصال بالخادم — تحقق من اتصالك بالإنترنت وحاول مرة أخرى';
  if (r.includes('الملف لم يُوجد') || r.includes('file not found'))
    return 'فشل في معالجة الفيديو — حاول بجودة مختلفة أو بعد لحظات';
  if (r.includes('rate') || r.includes('limit') || r.includes('too many'))
    return 'طلبات كثيرة — انتظر دقيقة ثم حاول مرة أخرى';
  return raw;
}

function showError(msg) {
  const box = document.getElementById('errorBox');
  document.getElementById('errorText').textContent = friendlyError(msg);
  box.classList.remove('hidden');
}

function hideError() {
  document.getElementById('errorBox').classList.add('hidden');
}

// ===== Theme Toggle =====
function toggleTheme() {
  const html = document.documentElement;
  const isLight = html.getAttribute('data-theme') === 'light';
  const next = isLight ? 'dark' : 'light';
  html.setAttribute('data-theme', next);
  localStorage.setItem('theme', next);
  document.getElementById('themeToggle').textContent = next === 'light' ? '🌙' : '☀️';
}
(function initTheme() {
  const saved = localStorage.getItem('theme') || 'dark';
  document.documentElement.setAttribute('data-theme', saved);
  const btn = document.getElementById('themeToggle');
  if (btn) btn.textContent = saved === 'light' ? '🌙' : '☀️';
})();

// ===== Confetti =====
function launchConfetti() {
  const colors = ['#7c3aed','#a855f7','#f59e0b','#10b981','#06b6d4','#f97316','#fff'];
  for (let i = 0; i < 90; i++) {
    const el = document.createElement('div');
    const size = Math.random() * 10 + 6;
    el.style.cssText = `
      position:fixed; top:-20px; left:${Math.random()*100}%;
      width:${size}px; height:${size}px;
      background:${colors[Math.floor(Math.random()*colors.length)]};
      border-radius:${Math.random()>.5?'50%':'3px'};
      animation: confettiFall ${Math.random()*2+1.5}s ease forwards;
      animation-delay:${Math.random()*.6}s;
      z-index:9998; pointer-events:none;
    `;
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 3500);
  }
}

// ===== Circular Progress =====
function setCircularProgress(pct) {
  const circle = document.getElementById('progressCircle');
  if (circle) circle.style.strokeDashoffset = 377 - (pct / 100) * 377;
}

function setLoading(loading) {
  const btn = document.getElementById('fetchBtn');
  btn.disabled = loading;
  btn.innerHTML = loading
    ? '<span class="btn-text">جاري الجلب...</span><span class="spinner"></span>'
    : '<span class="btn-text">جلب معلومات الفيديو</span><span class="btn-icon">→</span>';
}

function formatDuration(sec) {
  if (!sec) return '';
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  if (h) return `${h}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`;
  return `${m}:${String(s).padStart(2,'0')}`;
}

function formatSize(bytes) {
  if (!bytes) return '';
  if (bytes > 1e9) return (bytes / 1e9).toFixed(1) + ' GB';
  if (bytes > 1e6) return (bytes / 1e6).toFixed(0) + ' MB';
  return (bytes / 1e3).toFixed(0) + ' KB';
}

// ===== Fetch Info =====
async function fetchInfo() {
  const url = document.getElementById('urlInput').value.trim();
  if (!url) { showError('الرجاء إدخال رابط الفيديو'); return; }

  hideError();
  setLoading(true);
  document.getElementById('infoSection').classList.add('hidden');
  document.getElementById('progressSection').classList.add('hidden');
  document.getElementById('successSection').classList.add('hidden');
  document.getElementById('skeletonSection').classList.remove('hidden');

  try {
    const res = await fetch('/api/info', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url }),
    });
    const data = await res.json();
    document.getElementById('skeletonSection').classList.add('hidden');
    if (!res.ok) { showError(data.error || 'حدث خطأ'); setLoading(false); return; }

    currentUrl = url;
    currentFormats = data.formats || [];
    renderInfo(data);
    document.getElementById('infoSection').classList.remove('hidden');
  } catch {
    document.getElementById('skeletonSection').classList.add('hidden');
    showError('network');
  }
  setLoading(false);
}

function renderInfo(data) {
  const thumb = document.getElementById('thumbnail');
  const thumbWrap = document.getElementById('thumbWrap');
  currentThumbnail = data.thumbnail || '';
  if (data.thumbnail) {
    thumb.src = data.thumbnail;
    thumbWrap.style.display = '';
    thumb.onerror = () => { thumbWrap.style.display = 'none'; };
  } else {
    thumbWrap.style.display = 'none';
  }

  document.getElementById('videoTitle').textContent = data.title || 'فيديو';
  document.getElementById('platformBadge').textContent = data.platform || '';
  document.getElementById('uploaderEl').textContent = data.uploader ? '👤 ' + data.uploader : '';
  document.getElementById('durationEl').textContent = data.duration ? '⏱ ' + formatDuration(data.duration) : '';

  const grid = document.getElementById('formatsList');
  grid.innerHTML = '';
  selectedFormat = null;

  currentFormats.forEach(f => {
    const btn = document.createElement('button');
    btn.className = 'format-btn';
    btn.innerHTML = `
      <span class="format-label">${f.label}</span>
      <span class="format-type">${f.type === 'video' ? '🎬 فيديو' : '🎵 صوت'} · ${f.ext}</span>
      ${f.filesize ? `<span class="format-size">${formatSize(f.filesize)}</span>` : ''}
    `;
    btn.addEventListener('click', () => {
      document.querySelectorAll('.format-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      selectedFormat = f;
      document.getElementById('dlSelBtn').disabled = false;
    });
    grid.appendChild(btn);
  });

  if (currentFormats.length > 0) grid.firstChild.click();

  let dlBtn = document.getElementById('dlSelBtn');
  if (!dlBtn) {
    dlBtn = document.createElement('button');
    dlBtn.id = 'dlSelBtn';
    dlBtn.className = 'btn-download-sel';
    dlBtn.addEventListener('click', handleDownloadClick);
  }
  dlBtn.textContent = isPremium() ? '⬇️  تحميل فوري (مميز)' : '⬇️  تحميل (مجاني)';
  dlBtn.disabled = currentFormats.length === 0;

  const infoCard = document.querySelector('.info-card');
  if (!infoCard.contains(dlBtn)) infoCard.appendChild(dlBtn);

}

// ===== Download Flow =====
function handleDownloadClick() {
  if (!selectedFormat) return;
  if (isPremium()) {
    startDownload();
  } else {
    showAdModal();
  }
}

// ===== Ad Modal =====
let adTimer = null;

function showAdModal() {
  document.getElementById('adModal').classList.remove('hidden');
  document.getElementById('adOverlay').classList.remove('hidden');

  const countdownEl = document.getElementById('adCountdown');
  const skipBtn = document.getElementById('adSkipBtn');
  const progressFill = document.getElementById('adProgressFill');

  let seconds = (typeof AD_WAIT_SECONDS !== 'undefined') ? AD_WAIT_SECONDS : 10;
  skipBtn.disabled = true;
  countdownEl.textContent = seconds;
  progressFill.style.width = '0%';
  progressFill.style.transition = 'none';

  setTimeout(() => {
    progressFill.style.transition = `width ${seconds}s linear`;
    progressFill.style.width = '100%';
  }, 50);

  adTimer = setInterval(() => {
    seconds--;
    countdownEl.textContent = seconds;
    if (seconds <= 0) {
      clearInterval(adTimer);
      skipBtn.disabled = false;
      countdownEl.textContent = '0';
    }
  }, 1000);
}

function closeAdModal() {
  document.getElementById('adModal').classList.add('hidden');
  document.getElementById('adOverlay').classList.add('hidden');
  if (adTimer) clearInterval(adTimer);
}

function onAdFinished() {
  closeAdModal();
  startDownload();
}

// ===== Start Download =====
async function startDownload() {
  document.getElementById('infoSection').classList.add('hidden');
  document.getElementById('progressSection').classList.remove('hidden');
  setCircularProgress(0);
  document.getElementById('progressPercent').textContent = '0%';

  try {
    const res = await fetch('/api/download', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: currentUrl, format_id: selectedFormat.format_id }),
    });
    const data = await res.json();
    if (!res.ok) {
      showError(data.error || 'فشل التحميل');
      document.getElementById('progressSection').classList.add('hidden');
      document.getElementById('infoSection').classList.remove('hidden');
      return;
    }
    pollProgress(data.task_id);
  } catch {
    showError('network');
    document.getElementById('progressSection').classList.add('hidden');
    document.getElementById('infoSection').classList.remove('hidden');
  }
}

function pollProgress(taskId) {
  if (pollInterval) clearInterval(pollInterval);

  pollInterval = setInterval(async () => {
    try {
      const res = await fetch(`/api/progress/${taskId}`);
      const data = await res.json();

      if (data.status === 'downloading') {
        const pct = data.percent || 0;
        setCircularProgress(pct);
        document.getElementById('progressPercent').textContent = pct + '%';
        document.getElementById('progressSpeed').textContent = data.speed ? '⚡ ' + data.speed : '';
        document.getElementById('progressEta').textContent = data.eta ? '⏱ ' + data.eta : '';
      } else if (data.status === 'processing' || data.status === 'starting') {
        document.querySelector('.progress-label').textContent = 'جاري المعالجة...';
        setCircularProgress(90);
        document.getElementById('progressPercent').textContent = '90%';
      } else if (data.status === 'done') {
        clearInterval(pollInterval);
        document.getElementById('progressSection').classList.add('hidden');
        showSuccess(data.file, data.filename || 'video.mp4');
      } else if (data.status === 'error') {
        clearInterval(pollInterval);
        document.getElementById('progressSection').classList.add('hidden');
        document.getElementById('infoSection').classList.remove('hidden');
        showError(data.error || '');
      }
    } catch { /* keep polling */ }
  }, 1000);
}

let lastDownloadUrl = '';

function showSuccess(file, filename) {
  const link = document.getElementById('downloadLink');
  lastDownloadUrl = `/api/file/${file}?name=${encodeURIComponent(filename)}`;
  link.href = lastDownloadUrl;
  link.download = filename;

  const st = document.getElementById('successThumb');
  if (currentThumbnail) {
    st.src = currentThumbnail;
    st.classList.remove('hidden');
    st.onerror = () => st.classList.add('hidden');
  } else {
    st.classList.add('hidden');
  }

  document.getElementById('successSection').classList.remove('hidden');
  showDownloadHint();
  link.click();
  launchConfetti();
  saveToHistory(filename, currentUrl);
}

function showDownloadHint() {
  const hint = document.getElementById('downloadHint');
  if (!hint) return;
  const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) && !window.MSStream;
  const isAndroid = /Android/.test(navigator.userAgent);
  if (isIOS) {
    hint.innerHTML = `📂 <strong style="color:var(--text)">أين الفيديو؟ (iPhone)</strong><br>
      <strong>1.</strong> افتح تطبيق <strong>الملفات</strong> ← التنزيلات<br>
      <strong>2.</strong> اضغط مطولاً على الفيديو ← <strong>مشاركة</strong> ← <strong>احفظ الفيديو</strong><br>
      <span style="font-size:.75rem">بعدها سيظهر في تطبيق الصور مباشرة ✅</span>`;
  } else if (isAndroid) {
    hint.innerHTML = `📂 <strong style="color:var(--text)">أين الفيديو؟ (Android)</strong><br>
      افتح تطبيق <strong>الملفات</strong> ← مجلد <strong>Downloads</strong><br>
      <span style="font-size:.75rem">أو تطبيق الصور ← المكتبة ← التنزيلات ✅</span>`;
  } else {
    hint.innerHTML = `📂 <strong style="color:var(--text)">أين الفيديو؟</strong> تحقق من مجلد <strong>التنزيلات</strong> على جهازك`;
  }
}

function copyDownloadLink() {
  if (!lastDownloadUrl) return;
  const full = window.location.origin + lastDownloadUrl;
  navigator.clipboard.writeText(full).then(() => {
    const btn = document.getElementById('copyLinkBtn');
    btn.textContent = '✅ تم النسخ!';
    setTimeout(() => btn.textContent = '🔗 نسخ رابط التحميل', 2000);
  });
}

// ===== Download History =====
function saveToHistory(filename, url) {
  const history = JSON.parse(localStorage.getItem('dl_history') || '[]');
  history.unshift({ filename, url, date: new Date().toLocaleDateString('ar') });
  if (history.length > 10) history.pop();
  localStorage.setItem('dl_history', JSON.stringify(history));
  renderHistory();
}

function renderHistory() {
  const history = JSON.parse(localStorage.getItem('dl_history') || '[]');
  const section = document.getElementById('historySection');
  const list = document.getElementById('historyList');
  if (history.length === 0) { section.classList.add('hidden'); return; }
  section.classList.remove('hidden');
  list.innerHTML = history.map(h => `
    <div style="display:flex;align-items:center;gap:.8rem;background:var(--bg2);border-radius:8px;padding:.6rem 1rem;border:1px solid var(--border)">
      <span style="font-size:1.2rem">🎬</span>
      <div style="flex:1;min-width:0">
        <div style="font-size:.85rem;font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${h.filename}</div>
        <div style="font-size:.72rem;color:var(--muted)">${h.date}</div>
      </div>
      <button onclick="reFetch('${h.url}')" style="background:none;border:1px solid var(--border);border-radius:8px;padding:.3rem .7rem;color:var(--muted);font-size:.75rem;cursor:pointer;white-space:nowrap">إعادة جلب</button>
    </div>
  `).join('');
}

function clearHistory() {
  localStorage.removeItem('dl_history');
  renderHistory();
}

function reFetch(url) {
  document.getElementById('urlInput').value = url;
  window.scrollTo({ top: 0, behavior: 'smooth' });
  fetchInfo();
}

// ===== Public Stats =====
async function loadPublicStats() {
  try {
    const res = await fetch('/api/public-stats');
    const d = await res.json();
    const total = d.total_downloads;
    const display = total > 1000
      ? '+' + Math.floor(total / 1000) + ',000'
      : total > 0 ? '+' + total : '+10,000';
    document.getElementById('heroDownloads').textContent = display;
    if (d.rating_count > 0) {
      document.getElementById('heroRating').textContent = '⭐ ' + d.rating_avg;
      document.getElementById('heroRatingCount').textContent = d.rating_count + ' تقييم';
    }
  } catch {}
}
loadPublicStats();
renderHistory();

// ===== Rating =====
const stars = document.querySelectorAll('.star');
let selectedStars = 0;

function highlightStars(n, cls) {
  stars.forEach((s, i) => {
    s.classList.remove('active', 'hover-active');
    if (i < n) s.classList.add(cls || 'active');
  });
}

stars.forEach((s, idx) => {
  s.addEventListener('mouseenter', () => {
    highlightStars(idx + 1, 'hover-active');
  });
  s.addEventListener('mouseleave', () => {
    highlightStars(selectedStars, 'active');
  });
  s.addEventListener('click', () => {
    selectedStars = idx + 1;
    highlightStars(selectedStars, 'active');
    document.getElementById('submitRatingBtn').style.display = 'inline-block';
    document.getElementById('ratingMsg').textContent = '';
    document.getElementById('ratingMsg').style.color = 'var(--muted)';
  });
});

const userRated = localStorage.getItem('vip_rated');
if (userRated) {
  selectedStars = parseInt(userRated);
  highlightStars(selectedStars, 'active');
  document.getElementById('ratingMsg').textContent = 'قيّمت سابقاً — يمكنك تغيير تقييمك';
}

function submitRating() {
  if (!selectedStars) return;
  const btn = document.getElementById('submitRatingBtn');
  btn.disabled = true;
  btn.textContent = 'جاري الإرسال...';
  fetch('/api/rate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ stars: selectedStars }),
  }).then(r => r.json()).then(d => {
    localStorage.setItem('vip_rated', selectedStars);
    const msg = document.getElementById('ratingMsg');
    msg.textContent = d.message + ' — متوسط: ' + d.avg + ' ⭐ (' + d.count + ' تقييم)';
    msg.style.color = '#10b981';
    btn.style.display = 'none';
    loadPublicStats();
  }).catch(() => {
    btn.disabled = false;
    btn.textContent = 'إرسال التقييم ★';
  });
}

function resetPage() {
  if (pollInterval) clearInterval(pollInterval);
  document.getElementById('urlInput').value = '';
  document.getElementById('infoSection').classList.add('hidden');
  document.getElementById('progressSection').classList.add('hidden');
  document.getElementById('successSection').classList.add('hidden');
  document.getElementById('skeletonSection').classList.add('hidden');
  setCircularProgress(0);
  document.getElementById('progressPercent').textContent = '0%';
  hideError();
  currentUrl = '';
  currentFormats = [];
  currentThumbnail = '';
  selectedFormat = null;
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

// ===== Premium Token Check (after Stripe redirect) =====
(function checkPremiumToken() {
  const params = new URLSearchParams(window.location.search);
  const token = params.get('premium');
  if (token === 'success') {
    localStorage.setItem('vip_premium', 'true');
    window.history.replaceState({}, '', '/');
    showPremiumWelcome();
  }
})();

// ===== Pay Modal =====
function openPayModal() {
  document.getElementById('payModal').classList.remove('hidden');
  document.getElementById('payOverlay').classList.remove('hidden');
}

function closePayModal() {
  document.getElementById('payModal').classList.add('hidden');
  document.getElementById('payOverlay').classList.add('hidden');
}

// ===== Redeem Code =====
function openRedeemModal() {
  document.getElementById('redeemModal').classList.remove('hidden');
  document.getElementById('redeemOverlay').classList.remove('hidden');
  document.getElementById('redeemInput').value = '';
  document.getElementById('redeemMsg').textContent = '';
  setTimeout(() => document.getElementById('redeemInput').focus(), 100);
}

function closeRedeemModal() {
  document.getElementById('redeemModal').classList.add('hidden');
  document.getElementById('redeemOverlay').classList.add('hidden');
}

async function submitRedeemCode() {
  const code = document.getElementById('redeemInput').value.trim();
  const msgEl = document.getElementById('redeemMsg');
  if (!code) { msgEl.style.color = '#fca5a5'; msgEl.textContent = 'أدخل الكود أولاً'; return; }

  msgEl.style.color = 'var(--muted)';
  msgEl.textContent = 'جاري التحقق...';

  try {
    const res = await fetch('/api/redeem-code', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code }),
    });
    const data = await res.json();
    if (res.ok) {
      localStorage.setItem('vip_premium', 'true');
      localStorage.setItem('vip_code', code);
      if (data.expires_at) {
        localStorage.setItem('vip_expires', new Date(data.expires_at).getTime());
      }
      msgEl.style.color = '#10b981';
      msgEl.textContent = data.message;
      setTimeout(() => { closeRedeemModal(); showPremiumWelcome(); }, 1500);
    } else {
      msgEl.style.color = '#fca5a5';
      msgEl.textContent = data.error || 'خطأ غير معروف';
    }
  } catch {
    msgEl.style.color = '#fca5a5';
    msgEl.textContent = 'تعذّر الاتصال بالخادم';
  }
}

function showPremiumWelcome() {
  document.getElementById('premiumModal').classList.remove('hidden');
  document.getElementById('premiumOverlay').classList.remove('hidden');
}

function closePremiumModal() {
  document.getElementById('premiumModal').classList.add('hidden');
  document.getElementById('premiumOverlay').classList.add('hidden');
}

function shareWhatsApp() {
  const text = 'جربت موقع نزلها بلس — يحمّل فيديوهات TikTok وInstagram وFacebook وPinterest بجودة عالية وبدون واترمارك 🔥\n\nwww.vip-dl.com';
  window.open('https://wa.me/?text=' + encodeURIComponent(text), '_blank');
}

function shareNative() {
  const data = {
    title: 'نزلها بلس',
    text: 'حمّل فيديوهاتك بدون واترمارك من TikTok وInstagram وFacebook وPinterest',
    url: 'https://www.vip-dl.com',
  };
  if (navigator.share) {
    navigator.share(data).catch(() => {});
  } else {
    navigator.clipboard.writeText('https://www.vip-dl.com').then(() => {
      const btn = event.target.closest('button');
      const orig = btn.textContent;
      btn.textContent = '✅ تم نسخ الرابط!';
      setTimeout(() => btn.textContent = orig, 2000);
    });
  }
}
