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

// ===== Enter key =====
document.getElementById('urlInput').addEventListener('keydown', e => {
  if (e.key === 'Enter') fetchInfo();
});

// ===== State =====
let currentUrl = '';
let currentFormats = [];
let selectedFormat = null;
let pollInterval = null;

function showError(msg) {
  const box = document.getElementById('errorBox');
  document.getElementById('errorText').textContent = msg;
  box.classList.remove('hidden');
}

function hideError() {
  document.getElementById('errorBox').classList.add('hidden');
}

function setLoading(loading) {
  const btn = document.getElementById('fetchBtn');
  btn.disabled = loading;
  if (loading) {
    btn.innerHTML = '<span class="btn-text">جاري الجلب...</span><span class="spinner"></span>';
  } else {
    btn.innerHTML = '<span class="btn-text">جلب معلومات الفيديو</span><span class="btn-icon">→</span>';
  }
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

  try {
    const res = await fetch('/api/info', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url }),
    });
    const data = await res.json();

    if (!res.ok) { showError(data.error || 'حدث خطأ'); setLoading(false); return; }

    currentUrl = url;
    currentFormats = data.formats || [];
    renderInfo(data);
    document.getElementById('infoSection').classList.remove('hidden');
  } catch {
    showError('تعذّر الاتصال بالخادم، حاول مرة أخرى');
  }
  setLoading(false);
}

function renderInfo(data) {
  const thumb = document.getElementById('thumbnail');
  if (data.thumbnail) {
    thumb.src = data.thumbnail;
    thumb.onerror = () => { thumb.src = ''; thumb.style.display = 'none'; };
  } else {
    thumb.style.display = 'none';
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

  // Auto-select best
  if (currentFormats.length > 0) {
    grid.firstChild.click();
  }

  // Download button
  let dlBtn = document.getElementById('dlSelBtn');
  if (!dlBtn) {
    dlBtn = document.createElement('button');
    dlBtn.id = 'dlSelBtn';
    dlBtn.className = 'btn-download-sel';
    dlBtn.textContent = '⬇️  تحميل';
    dlBtn.addEventListener('click', startDownload);
  }
  dlBtn.disabled = currentFormats.length === 0;

  const infoCard = document.querySelector('.info-card');
  if (!infoCard.contains(dlBtn)) infoCard.appendChild(dlBtn);
}

// ===== Start Download =====
async function startDownload() {
  if (!selectedFormat) return;

  document.getElementById('infoSection').classList.add('hidden');
  document.getElementById('progressSection').classList.remove('hidden');
  document.getElementById('progressBar').style.width = '0%';
  document.getElementById('progressPercent').textContent = '0%';

  try {
    const res = await fetch('/api/download', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: currentUrl, format_id: selectedFormat.format_id }),
    });
    const data = await res.json();
    if (!res.ok) { showError(data.error || 'فشل التحميل'); return; }

    pollProgress(data.task_id);
  } catch {
    showError('تعذّر بدء التحميل');
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

      if (data.status === 'downloading' || data.status === 'processing' || data.status === 'starting') {
        const pct = data.percent || 0;
        document.getElementById('progressBar').style.width = pct + '%';
        document.getElementById('progressPercent').textContent = pct + '%';
        document.getElementById('progressSpeed').textContent = data.speed ? '⚡ ' + data.speed : '';
        document.getElementById('progressEta').textContent = data.eta ? '⏱ ' + data.eta : '';

        if (data.status === 'processing') {
          document.querySelector('.progress-label').textContent = 'جاري المعالجة...';
          document.getElementById('progressBar').style.width = '95%';
          document.getElementById('progressPercent').textContent = '95%';
        }
      } else if (data.status === 'done') {
        clearInterval(pollInterval);
        document.getElementById('progressSection').classList.add('hidden');
        showSuccess(data.file, data.filename || 'video.mp4');
      } else if (data.status === 'error') {
        clearInterval(pollInterval);
        document.getElementById('progressSection').classList.add('hidden');
        document.getElementById('infoSection').classList.remove('hidden');
        showError('فشل التحميل: ' + (data.error || 'خطأ غير معروف'));
      }
    } catch { /* keep polling */ }
  }, 1000);
}

function showSuccess(file, filename) {
  const link = document.getElementById('downloadLink');
  link.href = `/api/file/${file}?name=${encodeURIComponent(filename)}`;
  link.download = filename;
  document.getElementById('successSection').classList.remove('hidden');

  // Auto-trigger download
  link.click();
}

function resetPage() {
  if (pollInterval) clearInterval(pollInterval);
  document.getElementById('urlInput').value = '';
  document.getElementById('infoSection').classList.add('hidden');
  document.getElementById('progressSection').classList.add('hidden');
  document.getElementById('successSection').classList.add('hidden');
  hideError();
  currentUrl = '';
  currentFormats = [];
  selectedFormat = null;
  window.scrollTo({ top: 0, behavior: 'smooth' });
}
