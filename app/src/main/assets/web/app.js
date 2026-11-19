(() => {
  'use strict';
  const $ = (s) => document.querySelector(s);

  // ---------- Định danh thiết bị ----------
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch (e) {} }
  };
  let clientId = store.get('kb.id');
  if (!clientId) {
    clientId = (window.crypto && crypto.randomUUID) ? crypto.randomUUID()
      : Math.random().toString(36).slice(2) + Date.now().toString(36);
    store.set('kb.id', clientId);
  }
  let clientName = store.get('kb.name') || 'Khách';
  const whoBtn = $('#who');
  // Vai trò trong phòng do TV quyết định (người đầu tiên quét QR to là chủ phòng); điện thoại chỉ hiển thị
  let room = { isOwner: false, hasOwner: false, ownerName: '' };
  const renderWho = () => {
    whoBtn.textContent = (room.isOwner ? '👑 ' : '') + clientName;
    whoBtn.title = room.isOwner ? 'Bạn là chủ phòng. Bấm để đổi tên' : 'Đổi tên thiết bị';
    document.body.classList.toggle('is-owner', !!room.isOwner);
  };
  function applyRoom(r) {
    if (!r) return;
    room = { isOwner: !!r.isOwner, hasOwner: !!r.hasOwner, ownerName: r.ownerName || '' };
    renderWho();
    if (typeof renderGame === 'function' && gameCfg) renderGame();   // đổi chủ phòng: mở / khóa các nút trò chơi
  }
  renderWho();
  whoBtn.addEventListener('click', () => {
    const v = prompt('Tên hiển thị khi bạn thêm bài:', clientName);
    if (v && v.trim()) { clientName = v.trim().slice(0, 30); store.set('kb.name', clientName); renderWho(); sendWs('hello', { clientId, name: clientName }); }
  });

  // ---------- Trạng thái ----------
  let player = null, recvAt = 0;
  let queue = [], qVersion = -1;
  let favs = [];                 // bài yêu thích (SongDto)
  const favKeys = new Set();     // 'L:<id>' (bài trong máy) hoặc 'Y:<videoId>' (YouTube)

  // Làm gọn tiêu đề YouTube khi hiển thị: bỏ hashtag (#LIVETAISINH...) và dấu | - thừa ở đầu/cuối
  function cleanTitle(t) {
    const s = String(t == null ? '' : t)
      .replace(/(^|\s)#\p{L}[\p{L}\p{N}_]*/gu, ' ')
      .replace(/\s+/g, ' ')
      .replace(/^[\s|\-–—·:]+|[\s|\-–—·:]+$/g, '');
    return s || String(t == null ? '' : t).trim();
  }

  // ---------- API ----------
  async function api(method, path, body) {
    const opt = {
      method,
      headers: { 'X-Client-Id': clientId, 'X-Client-Name': encodeURIComponent(clientName) }
    };
    if (method === 'POST') { opt.headers['Content-Type'] = 'application/json; charset=utf-8'; opt.body = JSON.stringify(body || {}); }
    const r = await fetch(path, opt);
    let j;
    try { j = await r.json(); } catch (e) { throw new Error('Không đọc được phản hồi'); }
    if (!j.ok) throw new Error(j.error && j.error.message || 'Có lỗi xảy ra');
    return j.data;
  }
  const act = async (fn) => { try { await fn(); } catch (e) { toast(e.message); } };

  // ---------- Thông báo nhỏ ----------
  let toastTimer;
  function toast(msg) {
    const el = $('#toast');
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), 2400);
  }

  // ---------- Hiệu ứng "đã chọn": hàng bài nháy xanh + nút đổi thành dấu ✓ khoảng 2 giây ----------
  // Lưu theo id bài (không gắn vào phần tử) vì danh sách bị vẽ lại ngay khi hàng chờ cập nhật.
  const picked = new Map(), pickedTimers = new Map();
  const listRows = () => document.querySelectorAll('#songs li, #yt li');
  function setPickedState(li, state) {
    li.classList.toggle('picked', !!state);
    li.querySelectorAll('button[data-act="add"], button[data-act="prio"]').forEach((b) => {
      const a = b.dataset.act, on = state === a;
      b.classList.toggle('done', on);
      b.textContent = on ? (a === 'prio' ? '✓ Ưu tiên' : '✓ Đã chọn') : (a === 'prio' ? 'Ưu tiên' : 'Chọn');
    });
  }
  function reapplyPicked() {
    if (!picked.size) return;
    listRows().forEach((li) => { if (picked.has(li.dataset.id)) setPickedState(li, picked.get(li.dataset.id)); });
  }
  function markPicked(key, priority) {
    key = String(key);
    picked.set(key, priority ? 'prio' : 'add');
    listRows().forEach((li) => {
      if (li.dataset.id !== key) return;
      li.classList.remove('picked'); void li.offsetWidth;   // chọn lại cùng bài thì nháy lại từ đầu
      setPickedState(li, picked.get(key));
    });
    clearTimeout(pickedTimers.get(key));
    pickedTimers.set(key, setTimeout(() => {
      picked.delete(key); pickedTimers.delete(key);
      listRows().forEach((li) => { if (li.dataset.id === key) setPickedState(li, null); });
    }, 2200));
    try { if (navigator.vibrate) navigator.vibrate(35); } catch (_) {}
  }

  // Hàng chờ xếp công bằng theo người thêm nên bài mới có thể nằm giữa hàng: báo vị trí để khỏi thắc mắc
  function addedMsg(d, priority) {
    if (priority) return 'Đã đưa lên hát kế tiếp';
    const n = d && d.position;
    return n > 1 ? `Đã thêm vào danh sách chờ (bài thứ ${n})` : (n === 1 ? 'Đã thêm, sắp tới lượt hát' : 'Đã thêm vào danh sách chờ');
  }

  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const fmt = (ms) => { ms = Math.max(0, ms | 0); const s = Math.floor(ms / 1000); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); };

  // ---------- Hiệu ứng nhỏ (chỉ dùng transform / opacity / height) ----------
  // Nảy nhẹ: gỡ class khi xong để không bị chạy lại khi phần tử hiện lại sau display:none
  function bounce(el) {
    if (!el) return;
    el.classList.remove('pop');
    void el.offsetWidth;
    el.classList.add('pop');
    el.addEventListener('animationend', () => el.classList.remove('pop'), { once: true });
  }

  // Hàng bị xóa: thu chiều cao về 0 rồi mới gỡ khỏi DOM
  function collapseRow(li) {
    if (li.classList.contains('leaving')) return;
    li.style.height = li.offsetHeight + 'px';
    void li.offsetHeight;
    li.classList.add('leaving');
    li.style.height = '0px';
    setTimeout(() => li.remove(), 340);
  }

  // Đồng bộ danh sách theo khóa: giữ nguyên hàng cũ, chỉ thêm/sắp xếp lại, hàng biến mất thì thu lại mượt.
  // Hàng đang thu (.leaving) vẫn nằm yên tại chỗ cho tới khi xong.
  function reconcile(listEl, items, keyOf, innerOf, attr, skipEl) {
    const existing = new Map();
    [...listEl.children].forEach((li) => { if (!li.classList.contains('leaving')) existing.set(li._k, li); });
    const keys = items.map((it) => String(keyOf(it)));
    const keep = new Set(keys);
    // đánh dấu hàng bị xóa TRƯỚC khi sắp xếp, để chúng nằm yên tại chỗ trong lúc thu lại
    existing.forEach((li, key) => { if (!keep.has(key)) collapseRow(li); });
    let cursor = listEl.firstElementChild;
    items.forEach((it, i) => {
      const key = keys[i];
      const inner = innerOf(it, i);
      let li = existing.get(key);
      if (!li) {
        li = document.createElement('li');
        li._k = key;
        li.dataset[attr] = key;
        li.innerHTML = inner; li._h = inner;
      } else if (li._h !== inner && li !== skipEl) {
        li.innerHTML = inner; li._h = inner;
      }
      while (cursor && cursor.classList.contains('leaving')) cursor = cursor.nextElementSibling;
      if (li === cursor) cursor = cursor.nextElementSibling;
      else listEl.insertBefore(li, cursor);
    });
  }

  // ---------- Tabs (trượt ngang) ----------
  const TAB_ORDER = ['search', 'fav', 'queue'];
  let curTab = 'search';
  function showTab(name) {
    const panes = { search: '#paneSearch', fav: '#paneFav', queue: '#paneQueue' };
    const tabs = { search: '#tabSearch', fav: '#tabFav', queue: '#tabQueue' };
    const dir = TAB_ORDER.indexOf(name) >= TAB_ORDER.indexOf(curTab) ? 'r' : 'l';
    const changed = name !== curTab;
    for (const k in panes) { $(panes[k]).hidden = k !== name; $(tabs[k]).classList.toggle('on', k === name); }
    if (changed) {
      const pane = $(panes[name]), cls = dir === 'r' ? 'in-r' : 'in-l';
      pane.classList.remove('in-r', 'in-l');
      void pane.offsetWidth;
      pane.classList.add(cls);
      pane.addEventListener('animationend', () => pane.classList.remove(cls), { once: true });
    }
    curTab = name;
    $('#tabInd').style.transform = `translateX(${TAB_ORDER.indexOf(name) * 100}%)`;
    window.scrollTo(0, 0);
  }
  document.querySelectorAll('.tab').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));

  function syncTopHeight() {
    document.documentElement.style.setProperty('--top-h', $('.top').getBoundingClientRect().height + 'px');
    // chiều cao thanh tab: ô tìm kiếm ghim ngay dưới thanh này
    const tabsEl = $('.tabs');
    if (tabsEl && tabsEl.offsetHeight) document.documentElement.style.setProperty('--tabs-h', tabsEl.getBoundingClientRect().height + 'px');
  }
  new ResizeObserver(syncTopHeight).observe($('.top'));
  if ($('.tabs')) new ResizeObserver(syncTopHeight).observe($('.tabs'));

  // ---------- Yêu thích ----------
  const favsEl = $('#favs');
  // kết quả YouTube (chưa có id trong DB) chỉ có videoId; bài đã lưu có source = YOUTUBE
  const favKey = (s) => (s.source === 'YOUTUBE' || (s.videoId && s.id == null)) ? 'Y:' + s.videoId : 'L:' + s.id;
  const isFav = (s) => favKeys.has(favKey(s));
  const heartBtn = (on) => `<button class="heart${on ? ' on' : ''}" data-act="fav" aria-label="${on ? 'Bỏ yêu thích' : 'Thêm vào yêu thích'}">${on ? '♥' : '♡'}</button>`;

  function applyFavs(items) {
    favs = items || [];
    favKeys.clear();
    favs.forEach((f) => favKeys.add(favKey(f)));
    renderFavs();
    syncHearts();   // đổi trái tim tại chỗ (giữ nguyên phần tử đang nảy)
    if (player) renderPlayer();
  }

  function setHeart(btn, on) {
    if (!btn || btn.classList.contains('on') === on) return;
    btn.classList.toggle('on', on);
    btn.textContent = on ? '♥' : '♡';
    btn.setAttribute('aria-label', on ? 'Bỏ yêu thích' : 'Thêm vào yêu thích');
  }
  function syncHearts() {
    songsEl.querySelectorAll('li[data-id]').forEach((li) => setHeart(li.querySelector('.heart'), favKeys.has('L:' + li.dataset.id)));
    ytEl.querySelectorAll('li[data-id]').forEach((li) => setHeart(li.querySelector('.heart'), favKeys.has('Y:' + li.dataset.id)));
  }

  function favInner(f) {
    const known = f.artist && f.artist.trim() && f.artist !== 'Không rõ';
    return `<div class="meta"><b>${f.source === 'YOUTUBE' ? '<i class="ytb">YT</i>' : ''}${esc(cleanTitle(f.title))}</b>
        ${known ? `<span>${esc(f.artist.trim())}</span>` : ''}</div>
        <div class="facts">
          <button class="mini del" data-act="del">Xóa</button>
          <button class="mini add" data-act="add">Chọn</button>
          <button class="mini prio" data-act="prio">Ưu tiên</button>
        </div>`;
  }

  function renderFavs() {
    $('#fCount').textContent = favs.length;
    $('#favsEmpty').hidden = favs.length > 0;
    reconcile(favsEl, favs, favKey, favInner, 'key');
  }

  async function loadFavs() { try { applyFavs((await api('GET', '/api/favorites')).items); } catch (e) { /* bỏ qua */ } }

  // Bấm tim ở kết quả tìm kiếm: nếu đã yêu thích thì bỏ, chưa thì thêm
  async function toggleFav(s) {
    const cur = favs.find((f) => favKey(f) === favKey(s));
    if (cur) {
      const d = await api('DELETE', `/api/favorites/${cur.id}`);
      applyFavs(d.items); toast('Đã bỏ khỏi yêu thích');
    } else {
      const body = s.videoId && !s.id
        ? { videoId: s.videoId, title: s.title, channel: s.channel, durationMs: s.durationMs }
        : (s.source === 'YOUTUBE' ? { videoId: s.videoId, title: s.title, channel: s.artist, durationMs: s.durationMs } : { songId: s.id });
      const d = await api('POST', '/api/favorites', body);
      applyFavs(d.items); toast('Đã thêm vào yêu thích');
    }
  }

  favsEl.addEventListener('click', (e) => {
    const btn = e.target.closest('button[data-act]');
    if (!btn) return;
    const f = favs.find((x) => favKey(x) === btn.closest('li').dataset.key);
    if (!f) return;
    const a = btn.dataset.act;
    btn.disabled = true;
    act(async () => {
      try {
        if (a === 'del') {   // chỉ xóa khỏi danh sách yêu thích
          applyFavs((await api('DELETE', `/api/favorites/${f.id}`)).items);
          toast('Đã xóa khỏi yêu thích');
          return;
        }
        // Chọn / Ưu tiên: đẩy sang danh sách chờ, bài vẫn còn trong yêu thích
        const priority = a === 'prio';
        const d = f.source === 'YOUTUBE'
          ? await api('POST', '/api/youtube/queue', { videoId: f.videoId, title: f.title, channel: f.artist, durationMs: f.durationMs, priority })
          : await api('POST', '/api/queue', { songId: f.id, priority });
        toast(addedMsg(d, priority));
      } finally { btn.disabled = false; }
    });
  });

  // ---------- Tìm bài ----------
  const LIMIT = 40;
  let songs = [], total = 0, loading = false, searchToken = 0, lastQ = null;
  const songsEl = $('#songs');

  function countInQueue(id) { return queue.reduce((n, it) => n + (it.song.id === id ? 1 : 0), 0); }

  function songRow(s) {
    const n = countInQueue(s.id);
    const code = s.code ? s.code + ' · ' : '';
    const known = s.artist && s.artist.trim() && s.artist !== 'Không rõ';
    // chạm vào tên ca sĩ để xem tất cả bài của ca sĩ đó
    const artist = known ? `<a class="artist-link" data-artist="${esc(s.artist)}">${esc(s.artist)}</a>` : esc(s.artist);
    return `<li data-id="${s.id}">
      <div class="meta"><b>${esc(s.title)}${n ? `<span class="tag">Đã chọn${n > 1 ? ' ×' + n : ''}</span>` : ''}</b>
      <span>${esc(code)}${artist}</span></div>
      ${heartBtn(isFav(s))}
      <button class="btn prio" data-act="prio">Ưu tiên</button>
      <button class="btn add" data-act="add">Chọn</button></li>`;
  }

  function renderSongs() {
    songsEl.innerHTML = songs.map(songRow).join('');
    $('#songsEmpty').hidden = source !== 'local' || songs.length > 0 || loading;
    reapplyPicked();
  }

  async function loadSongs(reset) {
    const q = reset ? $('#q').value : (lastQ || '');
    if (reset) { songs = []; total = 0; lastQ = q; }
    const token = ++searchToken;
    loading = true;
    try {
      const d = await api('GET', `/api/songs?q=${encodeURIComponent(q)}&limit=${LIMIT}&offset=${songs.length}`);
      if (token !== searchToken) return;
      total = d.total;
      songs = songs.concat(d.items);
    } catch (e) { if (token === searchToken) toast(e.message); }
    loading = false;
    renderSongs();
  }

  let debounce;
  $('#q').addEventListener('input', () => { if (source !== 'local') return; clearTimeout(debounce); debounce = setTimeout(() => loadSongs(true), 200); });

  new IntersectionObserver((entries) => {
    if (entries[0].isIntersecting && !loading && songs.length < total) loadSongs(false);
  }, { rootMargin: '400px' }).observe($('#sentinel'));

  songsEl.addEventListener('click', (e) => {
    const link = e.target.closest('.artist-link');
    if (link) {
      $('#q').value = link.dataset.artist;
      window.scrollTo(0, 0);
      loadSongs(true);
      return;
    }
    const btn = e.target.closest('button[data-act]');
    if (!btn) return;
    const id = +btn.closest('li').dataset.id;
    if (btn.dataset.act === 'fav') {
      const s = songs.find((x) => x.id === id);
      if (s) { bounce(btn); act(() => toggleFav(s)); }
      return;
    }
    const priority = btn.dataset.act === 'prio';
    btn.disabled = true;
    act(async () => {
      try { const d = await api('POST', '/api/queue', { songId: id, priority }); toast(addedMsg(d, priority)); afterPick(id, priority); }
      finally { btn.disabled = false; }
    });
  });

  // ---------- YouTube ----------
  let source = 'yt';   // luôn bắt đầu bằng YouTube mỗi lần mở trang (không nhớ lựa chọn cũ)
  let useKaraoke = store.get('kb.ks') !== '0';
  let yt = [], ytCursor = null, ytBusy = false, ytToken = 0, ytSearched = false;
  const ytEl = $('#yt');

  function setSource(s) {
    source = s; hideSugg();
    const isYt = s === 'yt';
    $('#srcLocal').classList.toggle('on', !isYt);
    $('#srcYt').classList.toggle('on', isYt);
    $('#ksWrap').hidden = !isYt;
    $('#songs').hidden = isYt;
    $('#sentinel').hidden = isYt;
    $('#ytPane').hidden = !isYt;
    $('#q').placeholder = isYt ? 'Tìm bài trên YouTube (vd: lạc trôi karaoke)'
      : 'Tên bài hoặc ca sĩ (vd: lạc trôi, sơn tùng, lt)';
    if (isYt) { $('#songsEmpty').hidden = true; renderYt(); } else { renderSongs(); }
  }
  $('#ks').checked = useKaraoke;
  $('#ks').addEventListener('change', (e) => { useKaraoke = e.target.checked; store.set('kb.ks', useKaraoke ? '1' : '0'); });
  document.querySelectorAll('.segbtn[data-src]').forEach((b) => b.addEventListener('click', () => setSource(b.dataset.src)));

  function ytRow(v) {
    const n = queue.reduce((c, it) => c + (it.song.videoId === v.videoId ? 1 : 0), 0);
    const id = esc(v.videoId);
    return `<li data-id="${id}">
      <img class="thumb" loading="lazy" alt="" src="https://i.ytimg.com/vi/${id}/mqdefault.jpg" onerror="this.style.visibility='hidden'">
      <div class="meta"><b>${esc(cleanTitle(v.title))}</b>
      <span>${esc(v.channel)} · ${fmt(v.durationMs)}${n ? `<em class="tag">Đã chọn${n > 1 ? ' ×' + n : ''}</em>` : ''}</span></div>
      ${heartBtn(favKeys.has('Y:' + v.videoId))}
      <div class="acts">
        <button class="btn add" data-act="add">Chọn</button>
        <button class="btn prio" data-act="prio">Ưu tiên</button>
      </div></li>`;
  }

  function renderYt() {
    ytEl.innerHTML = yt.map(ytRow).join('');
    $('#ytHint').hidden = ytSearched || ytBusy;
    $('#ytBusy').hidden = !(ytBusy && yt.length === 0);
    $('#ytEmpty').hidden = !(ytSearched && !ytBusy && yt.length === 0);
    $('#ytMore').hidden = !ytCursor;
    $('#ytMore').disabled = ytBusy;
    reapplyPicked();
  }

  async function ytSearch(more) {
    if (source !== 'yt') return;
    const raw = $('#q').value.trim();
    if (!more) {
      if (raw.length < 2) return;   // ô trống hoặc mới gõ 1 chữ: giữ nguyên danh sách cũ
      yt = []; ytCursor = null;
    } else if (!ytCursor || ytBusy) { return; }

    const query = raw + (useKaraoke && !/karaoke/i.test(raw) ? ' karaoke' : '');
    const token = ++ytToken;
    ytBusy = true; renderYt();
    try {
      const url = more ? `/api/youtube/search?cursor=${encodeURIComponent(ytCursor)}`
                       : `/api/youtube/search?q=${encodeURIComponent(query)}`;
      const d = await api('GET', url);
      if (token !== ytToken) return;
      yt = more ? yt.concat(d.items) : d.items;
      ytCursor = d.cursor || null;
      ytSearched = true;
    } catch (e) {
      if (token === ytToken) { toast(e.message); if (!more) ytSearched = true; }
    }
    if (token === ytToken) { ytBusy = false; renderYt(); }
  }

  // --- Gợi ý khi gõ (autocomplete của YouTube) ---
  const suggEl = $('#sugg');
  let suggToken = 0, suggTimer;
  function hideSugg() { suggToken++; suggEl.hidden = true; suggEl.innerHTML = ''; }
  async function loadSugg() {
    const raw = $('#q').value.trim();
    if (source !== 'yt' || raw.length < 2) { hideSugg(); return; }
    const token = ++suggToken;
    try {
      const d = await api('GET', `/api/youtube/suggest?q=${encodeURIComponent(raw)}`);
      if (token !== suggToken || document.activeElement !== $('#q')) return;
      suggEl.innerHTML = d.items.map((t) => `<li data-q="${esc(t)}"><span class="mag">⌕</span>${esc(t)}</li>`).join('');
      suggEl.hidden = d.items.length === 0;
    } catch (e) { /* gợi ý là tính năng phụ: lỗi thì bỏ qua */ }
  }
  // giữ bàn phím mở khi chạm vào gợi ý
  suggEl.addEventListener('pointerdown', (e) => e.preventDefault());
  suggEl.addEventListener('click', (e) => {
    const li = e.target.closest('li[data-q]');
    if (!li) return;
    $('#q').value = li.dataset.q;
    hideSugg(); clearTimeout(ytDebounce); $('#q').blur(); ytSearch(false);
  });

  let ytDebounce;
  $('#q').addEventListener('input', () => {
    if (source !== 'yt') return;
    clearTimeout(suggTimer); suggTimer = setTimeout(loadSugg, 250);
    clearTimeout(ytDebounce);
    ytDebounce = setTimeout(() => ytSearch(false), 700);   // tự hiện kết quả khi ngừng gõ
  });
  $('#q').addEventListener('keydown', (e) => {
    if (source === 'yt' && e.key === 'Enter') { e.preventDefault(); clearTimeout(ytDebounce); hideSugg(); $('#q').blur(); ytSearch(false); }
  });

  // --- Chế độ gõ: khi bàn phím mở, ẩn tiêu đề + thanh điều khiển để nhường chỗ ---
  let typingTimer;
  $('#q').addEventListener('focus', () => {
    clearTimeout(typingTimer);
    document.body.classList.add('typing');
    window.scrollTo(0, 0);
    if (source === 'yt') loadSugg();
  });
  $('#q').addEventListener('blur', () => {
    // trễ một chút để cú chạm vào kết quả/gợi ý vẫn trúng nút cũ
    typingTimer = setTimeout(() => { document.body.classList.remove('typing'); hideSugg(); }, 350);
  });
  $('#ytMore').addEventListener('click', () => ytSearch(true));

  // --- Tự xóa kết quả sau khi chọn bài (cài đặt riêng của mỗi điện thoại, mặc định bật) ---
  let clearAfterPick = store.get('kb.clearPick') !== '0';
  $('#setClearAfterPick').checked = clearAfterPick;
  $('#setClearAfterPick').addEventListener('change', (e) => {
    clearAfterPick = e.target.checked;
    store.set('kb.clearPick', clearAfterPick ? '1' : '0');
  });
  // --- Tìm bằng giọng nói (nhận giọng của trình duyệt trên điện thoại, tiếng Việt) ---
  const SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition;
  let rec = null, listening = false;
  const VOICE_ERR = {
    'not-allowed': 'Chưa được cấp quyền micro. Hãy cho phép micro cho trang này rồi thử lại.',
    'service-not-allowed': 'Trình duyệt không cho nhận giọng nói ở trang này. Hãy dùng biểu tượng micro trên bàn phím.',
    'no-speech': 'Không nghe thấy giọng nói, hãy thử lại.',
    'audio-capture': 'Không tìm thấy micro trên điện thoại.',
    'network': 'Nhận giọng nói cần điện thoại có kết nối Internet.',
    'language-not-supported': 'Trình duyệt chưa hỗ trợ nhận giọng tiếng Việt.'
  };
  function searchNow() {
    hideSugg(); clearTimeout(debounce); clearTimeout(ytDebounce);
    if (source === 'yt') { $('#q').blur(); ytSearch(false); } else loadSongs(true);
  }
  function startVoice() {
    if (!SpeechRec) { toast('Trình duyệt này chưa hỗ trợ tìm bằng giọng nói. Hãy bấm biểu tượng micro trên bàn phím điện thoại.'); return; }
    if (listening) { try { rec.stop(); } catch (_) {} return; }
    const q = $('#q'), btn = $('#micBtn'), prevPh = q.placeholder;
    let text = '';
    try {
      rec = new SpeechRec();
      rec.lang = 'vi-VN'; rec.interimResults = true; rec.continuous = false; rec.maxAlternatives = 1;
      rec.onstart = () => {
        listening = true; btn.classList.add('on'); btn.setAttribute('aria-pressed', 'true');
        q.placeholder = 'Đang nghe… hãy nói tên bài hoặc ca sĩ';
      };
      rec.onresult = (e) => {
        let t = '';
        for (let i = 0; i < e.results.length; i++) t += e.results[i][0].transcript;
        text = t.trim(); q.value = text;   // hiện chữ ngay khi đang nói
      };
      rec.onerror = (e) => { if (e.error !== 'aborted') toast(VOICE_ERR[e.error] || ('Không nhận được giọng nói (' + e.error + ').')); };
      rec.onend = () => {
        listening = false; btn.classList.remove('on'); btn.setAttribute('aria-pressed', 'false');
        q.placeholder = prevPh;
        text = text.replace(/[.,!?;:。]+$/g, '').trim();
        if (text) { q.value = text; searchNow(); }
      };
      hideSugg(); q.blur();
      rec.start();
    } catch (err) { listening = false; toast('Không bật được nhận giọng nói. Hãy dùng micro trên bàn phím.'); }
  }
  $('#micBtn').addEventListener('click', startVoice);

  // Gọi sau khi Chọn / Ưu tiên thành công: báo rõ bằng hiệu ứng trên hàng bài, rồi (nếu bật) xóa chữ trong ô
  // tìm kiếm. Danh sách kết quả được GIỮ NGUYÊN cho đến khi tìm bài hoặc ca sĩ khác. Không tự mở bàn phím:
  // người dùng chạm vào ô tìm kiếm khi muốn gõ bài mới.
  function afterPick(key, priority) {
    markPicked(key, priority);
    if (!clearAfterPick) return;
    clearTimeout(debounce); clearTimeout(ytDebounce); clearTimeout(suggTimer);
    $('#q').value = '';   // gán bằng code nên không phát sự kiện input, danh sách không bị tìm lại
    hideSugg();
  }

  ytEl.addEventListener('click', (e) => {
    const btn = e.target.closest('button[data-act]');
    if (!btn) return;
    const v = yt.find((x) => x.videoId === btn.closest('li').dataset.id);
    if (!v) return;
    if (btn.dataset.act === 'fav') { bounce(btn); act(() => toggleFav(v)); return; }
    const priority = btn.dataset.act === 'prio';
    btn.disabled = true;
    act(async () => {
      try {
        const d = await api('POST', '/api/youtube/queue', { videoId: v.videoId, title: v.title, channel: v.channel, durationMs: v.durationMs, priority });
        toast(addedMsg(d, priority));
        afterPick(v.videoId, priority);
      } finally { btn.disabled = false; }
    });
  });

  // ---------- Hàng đợi ----------
  const queueEl = $('#queue');
  function renderQueue() {
    $('#qCount').textContent = queue.length;
    $('#queueEmpty').hidden = queue.length > 0;
    $('#qHint').hidden = queue.length < 2;
    $('#qClear').hidden = queue.length === 0;
    reconcile(queueEl, queue, (it) => it.itemId, (it, i) => {
      const artist = it.song.artist && it.song.artist.trim() && it.song.artist !== 'Không rõ' ? it.song.artist.trim() : '';
      return `
        <span class="no${i === 0 ? ' first' : ''}">${i + 1}</span>
        <div class="meta">
          <b>${it.song.source === 'YOUTUBE' ? '<i class="ytb">YT</i>' : ''}${esc(cleanTitle(it.song.title))}</b>
          ${artist ? `<span class="artist">${esc(artist)}</span>` : ''}
          <span class="by">${i === 0 ? '<em class="next">Hát kế tiếp</em> · ' : ''}Thêm bởi ${esc(it.addedBy)}</span>
        </div>
        <div class="qacts">
          ${i > 0 ? '<button class="mini totop" data-act="top">Lên đầu</button>' : ''}
          <button class="mini del" data-act="del">Xóa</button>
        </div>
        <span class="grip" aria-label="Kéo để đổi thứ tự">⠿</span>
      `;
    }, 'id', drag && drag.li);
    if (songs.length) renderSongs();   // cập nhật nhãn "Đã chọn"
    if (yt.length && typeof renderYt === 'function') renderYt();
    if (player) renderPlayer();   // cập nhật dòng nhắc "bấm Phát" khi hàng chờ đổi
  }

  queueEl.addEventListener('click', (e) => {
    const btn = e.target.closest('button[data-act]');
    if (!btn) return;
    const id = btn.closest('li').dataset.id;
    if (btn.dataset.act === 'top') act(() => api('POST', `/api/queue/${id}/top`, {}));
    if (btn.dataset.act === 'del') act(() => api('DELETE', `/api/queue/${id}`));
  });
  $('#qClear').addEventListener('click', () => { if (confirm('Xóa toàn bộ danh sách chờ?')) act(() => api('DELETE', '/api/queue')); });

  // Kéo thả đổi thứ tự bằng Pointer Events (tay cầm ⠿)
  let drag = null;
  queueEl.addEventListener('pointerdown', (e) => {
    const g = e.target.closest('.grip');
    if (!g) return;
    e.preventDefault();
    const li = g.closest('li');
    g.setPointerCapture(e.pointerId);
    drag = { li, id: +li.dataset.id, startY: e.clientY };
    li.classList.add('dragging');
  });
  queueEl.addEventListener('pointermove', (e) => {
    if (!drag) return;
    drag.li.style.transform = `translateY(${e.clientY - drag.startY}px)`;
  });
  const endDrag = async (e, commit) => {
    if (!drag) return;
    const { li, id } = drag;
    const rect = li.getBoundingClientRect();
    const cy = rect.top + rect.height / 2;
    const rows = [...queueEl.children].filter((r) => !r.classList.contains('leaving'));
    const from = rows.indexOf(li);
    let idx = 0;
    rows.filter((r) => r !== li).forEach((r) => { const b = r.getBoundingClientRect(); if (b.top + b.height / 2 < cy) idx++; });
    li.classList.remove('dragging');
    li.style.transform = '';
    drag = null;
    if (commit && idx !== from) await act(() => api('POST', '/api/queue/move', { itemId: id, toIndex: idx }));
  };
  queueEl.addEventListener('pointerup', (e) => endDrag(e, true));
  queueEl.addEventListener('pointercancel', (e) => endDrag(e, false));

  // ---------- Bài đang phát + dock ----------
  function renderPlayer() {
    const p = player;
    if (!p) return;
    const np = p.nowPlaying;
    const nt = $('#nowTitle');
    const shown = np ? cleanTitle(np.title) : 'Chưa có bài nào';
    if (nt.textContent !== shown) { nt.textContent = shown; nt.classList.remove('open'); }
    nt.classList.toggle('long', shown.length > 42);
    let sub = queue.length ? `Có ${queue.length} bài đang chờ · bấm Phát để bắt đầu hát` : 'Tìm bài và bấm Chọn để bắt đầu hát';
    if (np) {
      const st = { LOADING: 'Đang tải…', PAUSED: 'Đang tạm dừng', ENDED: 'Đã hết bài', PLAYING: '' }[p.status] || '';
      sub = np.artist + (st ? ' · ' + st : '');
    }
    $('#nowSub').textContent = sub;

    const nf = $('#nowFav');
    nf.hidden = !np;
    if (np) { const on = isFav(np); nf.classList.toggle('on', on); nf.textContent = on ? '♥' : '♡'; nf.setAttribute('aria-label', on ? 'Bỏ bài đang hát khỏi yêu thích' : 'Thêm bài đang hát vào yêu thích'); }

    $('#reacts').hidden = !(np && p.status === 'PLAYING');   // nút cảm xúc chỉ hiện khi bài đang hát
    const playing = p.status === 'PLAYING' || p.status === 'LOADING';
    $('#icPlay').hidden = playing;
    $('#icPause').hidden = !playing;
    $('#lblToggle').textContent = playing ? 'Tạm dừng' : 'Phát';

    if (!sliding) { $('#vol').value = p.volume; setVolVisual(+$('#vol').value); }
    $('#bar').classList.toggle('live', p.status === 'PLAYING');   // ánh sáng chỉ chạy khi đang hát
    renderTone(p);
    tick();
  }

  // Nút cảm xúc: tim / vỗ tay / pháo / quẩy bay lên TV. Chặn bấm quá dày ở máy; server cũng giới hạn tốc độ.
  let lastReact = 0;
  $('#reacts').addEventListener('click', (e) => {
    const b = e.target.closest('button[data-r]');
    if (!b) return;
    const now = Date.now();
    if (now - lastReact < 250) return;
    lastReact = now;
    b.classList.add('pop');
    setTimeout(() => b.classList.remove('pop'), 180);
    if (navigator.vibrate) { try { navigator.vibrate(12); } catch (err) {} }
    act(() => api('POST', '/api/react', { kind: b.dataset.r }));
  });

  function tick() {
    const p = player;
    if (!p || !p.nowPlaying || !p.durationMs) { $('#barFill').style.width = '0'; return; }
    let pos = p.positionMs;
    if (p.status === 'PLAYING') pos += Date.now() - recvAt;
    $('#barFill').style.width = Math.min(100, (pos / p.durationMs) * 100) + '%';
  }
  setInterval(tick, 500);

  // Chạm vào tiêu đề để mở rộng / thu gọn khi tên quá dài
  $('#nowTitle').addEventListener('click', () => { $('#nowTitle').classList.toggle('open'); syncTopHeight(); });
  $('#nowTitle').addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); $('#nowTitle').click(); } });

  $('#nowFav').addEventListener('click', () => { const np = player && player.nowPlaying; if (np) { bounce($('#nowFav')); act(() => toggleFav(np)); } });
  $('#btnToggle').addEventListener('click', () => act(() => api('POST', '/api/player/toggle', {})));
  $('#btnNext').addEventListener('click', () => act(() => api('POST', '/api/player/next', {})));
  $('#btnReplay').addEventListener('click', () => act(() => api('POST', '/api/player/replay', {})));
  // Nút "Hiện QR": GIỮ = QR trên TV rõ lên, THẢ TAY = mờ lại như ban đầu
  (() => {
    const b = $('#btnQr');
    let on = false, chain = Promise.resolve();
    const set = (v) => {
      if (on === v) return;
      on = v; b.classList.toggle('hold', v);
      // xếp hàng để lệnh "giữ" và "thả" luôn đến TV đúng thứ tự
      chain = chain.then(() => api('POST', '/api/qr/show', { on: v })).catch((e) => { if (v) toast(e.message); });
    };
    b.addEventListener('pointerdown', (e) => { e.preventDefault(); try { b.setPointerCapture(e.pointerId); } catch (x) {} set(true); });
    ['pointerup', 'pointercancel', 'lostpointercapture'].forEach((ev) => b.addEventListener(ev, () => set(false)));
    b.addEventListener('contextmenu', (e) => e.preventDefault());
    document.addEventListener('visibilitychange', () => { if (document.hidden) set(false); });
    window.addEventListener('pagehide', () => set(false));
  })();

  // Tone (nửa cung) và tốc độ: bảng nổi trên dock, nhớ tone riêng từng bài
  (() => {
    const sheet = $('#toneSheet'), btn = $('#btnTone');
    btn.addEventListener('click', () => { sheet.hidden = !sheet.hidden; btn.classList.toggle('hold', !sheet.hidden); });
    const key = (body) => act(() => api('POST', '/api/player/key', body));
    const tempo = (body) => act(() => api('POST', '/api/player/tempo', body));
    $('#keyDown').addEventListener('click', () => key({ delta: -1 }));
    $('#keyUp').addEventListener('click', () => key({ delta: 1 }));
    $('#keyReset').addEventListener('click', () => key({ value: 0 }));
    $('#tempoDown').addEventListener('click', () => tempo({ delta: -5 }));
    $('#tempoUp').addEventListener('click', () => tempo({ delta: 5 }));
    $('#tempoReset').addEventListener('click', () => tempo({ value: 100 }));
  })();
  function renderTone(p) {
    const k = p.keyShift || 0, t = p.tempoPct || 100;
    $('#keyVal').textContent = (k > 0 ? '+' : '') + k;
    $('#tempoVal').textContent = t + '%';
    $('#lblTone').textContent = k ? 'Tone ' + (k > 0 ? '+' : '') + k : 'Tone';
  }

  // ---------- Cài đặt chỉnh lúc đang hát (đổi là TV áp dụng ngay; mọi điện thoại và TV dùng chung) ----------
  let settings = null;
  const setModal = $('#setModal');
  function renderSettings() {
    const s = settings;
    if (!s) return;
    document.querySelectorAll('#setVocal .segbtn').forEach((b) => b.classList.toggle('on', (b.dataset.v === '1') === !!s.leftIsVocal));
    $('#setFair').checked = !!s.fairQueue;
    $('#setGuestSkip').checked = s.guestCanSkip !== false;

    // Hiệu ứng âm thanh phòng thu: danh sách chỉ dựng lại khi đổi (tránh làm đóng ô chọn đang mở)
    const fx = $('#setFx'), fxModes = s.audioFxModes || [];
    const fxSig = fxModes.map((m) => m.key + '|' + m.label).join(';');
    if (fx._sig !== fxSig) {
      fx._sig = fxSig;
      fx.innerHTML = fxModes.map((m) => `<option value="${esc(m.key)}">${esc(m.label)}</option>`).join('');
    }
    fx.value = s.audioFx;
    const fxCur = fxModes.find((m) => m.key === s.audioFx);
    $('#setFxNote').textContent = fxCur ? fxCur.note : '';
    const fxLv = $('#setFxLevel');
    if (document.activeElement !== fxLv) {   // đang kéo thì không giật thanh trượt
      fxLv.value = s.audioFxLevel;
      $('#setFxLevelVal').textContent = s.audioFxLevel + '%';
    }
    $('#setFxBox').classList.toggle('off', s.audioFx === 'OFF');

    const yt = $('#setYt'), heights = s.heightOptions || [];
    if (yt.children.length !== heights.length) {
      yt.innerHTML = heights.map((h) => `<button class="segbtn" data-h="${h}">${h}p</button>`).join('');
    }
    [...yt.children].forEach((b) => b.classList.toggle('on', +b.dataset.h === s.youtubeMaxHeight));

    // Chỉ dựng lại danh sách giao diện khi nó thật sự đổi (dựng lại lúc đang mở ô chọn sẽ làm ô chọn đóng)
    const sel = $('#setTheme'), modes = s.themeModes || [];
    const sig = modes.map((m) => m.key + '|' + m.label).join(';') + '#' + s.stageThemeNow;
    if (sel._sig !== sig) {
      sel._sig = sig;
      sel.innerHTML = modes.map((m) =>
        `<option value="${esc(m.key)}">${esc(m.key === 'AUTO' ? m.label + ' · đang dùng: ' + s.stageThemeNow : m.label)}</option>`).join('');
    }
    sel.value = s.stageTheme;
  }
  async function loadSettings() {
    try { settings = await api('GET', '/api/settings'); renderSettings(); } catch (e) { /* bỏ qua */ }
  }
  // Cập nhật ngay trên màn hình (không chờ mạng), lỗi thì trả về giá trị cũ
  async function saveSetting(patch) {
    if (!settings) return;
    const prev = settings;
    settings = Object.assign({}, settings, patch);
    renderSettings();
    try { settings = await api('POST', '/api/settings', patch); renderSettings(); }
    catch (e) { settings = prev; renderSettings(); toast(e.message); }
  }
  // Phiên bản app + kiểm tra cập nhật + đặt lại mặc định
  let appSigner = '';   // 6 byte đầu SHA-256 khoá ký của app trên box (so với dòng "Khoá ký" trong ghi chú release)
  const verText = (v, np) => 'Phiên bản: v' + v + (np ? ' (NewPipe ' + np + ')' : '') + (appSigner ? ' · Khoá ký: ' + appSigner : '');
  async function loadVersion() {
    try {
      const i = await api('GET', '/api/info');
      appSigner = i.signer || '';
      $('#setVer').textContent = verText(i.appVersion, i.newpipeVersion);
    } catch (e) { $('#setVer').textContent = 'Phiên bản: không đọc được'; }
  }
  // Sao lưu / khôi phục: file nằm trên điện thoại nên còn nguyên khi gỡ cài lại app trên box
  const backupMsg = $('#setBackupMsg');
  const showBackupMsg = (t, err) => { backupMsg.hidden = false; backupMsg.className = 'setnote' + (err ? ' err' : ''); backupMsg.textContent = t; };
  const readFileText = (f) => new Promise((res, rej) => {
    const fr = new FileReader();
    fr.onload = () => res(String(fr.result || ''));
    fr.onerror = () => rej(new Error('Không đọc được file'));
    fr.readAsText(f);
  });
  $('#setBackup').addEventListener('click', async () => {
    const btn = $('#setBackup');
    btn.disabled = true;
    try {
      const data = await api('GET', '/api/backup');
      const d = new Date(), pad = (n) => String(n).padStart(2, '0');
      const name = 'karaokebox-backup-' + d.getFullYear() + pad(d.getMonth() + 1) + pad(d.getDate()) + '.json';
      const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 1)], { type: 'application/json' }));
      const a = document.createElement('a');
      a.href = url; a.download = name;
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
      showBackupMsg('Đã tạo file ' + name + ' (' + data.favorites.length + ' bài Yêu thích + cài đặt). Tìm trong thư mục Tải xuống của điện thoại và cất kỹ.');
    } catch (e) { showBackupMsg(e.message, true); }
    finally { btn.disabled = false; }
  });
  $('#setRestore').addEventListener('click', () => $('#setRestoreFile').click());
  $('#setRestoreFile').addEventListener('change', async (e) => {
    const f = e.target.files && e.target.files[0];
    e.target.value = '';
    if (!f) return;
    if (f.size > 2 * 1024 * 1024) { showBackupMsg('File quá lớn, đây không phải file sao lưu của Karaoke Box.', true); return; }
    let data;
    try { data = JSON.parse(await readFileText(f)); }
    catch (x) { showBackupMsg('Không đọc được file: không phải file sao lưu hợp lệ.', true); return; }
    if (!data || data.app !== 'KaraokeBox') { showBackupMsg('Đây không phải file sao lưu của Karaoke Box.', true); return; }
    const btn = $('#setRestore');
    btn.disabled = true;
    showBackupMsg('Đang khôi phục…');
    try {
      const r = await api('POST', '/api/backup/restore', data);
      let t = 'Đã khôi phục: thêm ' + r.added + ' bài Yêu thích' + (r.existed ? ', ' + r.existed + ' bài đã có sẵn' : '') +
        (r.skipped ? ', bỏ qua ' + r.skipped + ' mục lỗi' : '') + '.';
      if (r.notInLibrary) t += ' ' + r.notInLibrary + ' bài trong máy sẽ hiện lại sau khi box quét thư viện nhạc.';
      if (r.settingsApplied) t += ' Đã áp dụng cài đặt.';
      else if (r.settingsSkipped) t += ' Cài đặt chưa áp dụng vì chỉ chủ phòng khôi phục được.';
      showBackupMsg(t);
    } catch (x) { showBackupMsg(x.message, true); }
    finally { btn.disabled = false; }
  });

  // Tải và cài bản mới ngay từ điện thoại: box tải APK rồi mở trình cài đặt trên TV (người dùng bấm "Cài đặt" ở TV)
  const instBtn = $('#setInstallUpd'), instState = $('#setInstState');
  let instArmed = false, instTimer, instPoll;
  const showInstallBtn = (on) => { instBtn.hidden = !(on && room.isOwner); };
  function disarmInstall() { instArmed = false; clearTimeout(instTimer); instBtn.classList.remove('armed'); instBtn.textContent = 'Tải và cài bản mới'; }
  function renderInstall(st) {
    if (!st || !st.phase || st.phase === 'IDLE') { instState.hidden = true; instBtn.disabled = false; return; }
    instState.hidden = false;
    instState.className = 'setnote' + (st.phase === 'ERROR' ? ' err' : st.phase === 'INSTALLING' ? ' new' : '');
    instState.textContent = st.message || '';
    instBtn.disabled = st.phase === 'DOWNLOADING';
  }
  // Đang tải thì hỏi tiến độ bằng HTTP (phòng khi WebSocket đang đứt); dừng khi xong hoặc đóng bảng
  function pollInstall() {
    clearInterval(instPoll);
    instPoll = setInterval(async () => {
      if (setModal.hidden) { clearInterval(instPoll); return; }
      try {
        const st = await api('GET', '/api/app/update/status');
        renderInstall(st);
        if (st.phase !== 'DOWNLOADING') clearInterval(instPoll);
      } catch (e) { /* box có thể đang khởi động lại để cài */ }
    }, 2000);
  }
  instBtn.addEventListener('click', async () => {
    if (!instArmed) {   // bấm hai lần vì cài bản mới sẽ tạm dừng bài đang hát
      instArmed = true; instBtn.classList.add('armed');
      instBtn.textContent = 'Bấm lần nữa: sẽ tạm dừng hát để cài';
      instTimer = setTimeout(disarmInstall, 4000);
      return;
    }
    disarmInstall();
    instBtn.disabled = true;
    try {
      const st = await api('POST', '/api/app/update/install', {});
      renderInstall(st);
      if (st.phase === 'DOWNLOADING') pollInstall();
    } catch (e) { toast(e.message); instBtn.disabled = false; }
  });
  let checkingUpd = false;
  $('#setCheckUpd').addEventListener('click', async () => {
    if (checkingUpd) return;
    checkingUpd = true;
    const btn = $('#setCheckUpd'), out = $('#setUpd');
    btn.disabled = true; btn.textContent = 'Đang kiểm tra…';
    out.hidden = false; out.className = 'setnote'; out.textContent = 'Đang kiểm tra bản mới của Karaoke Box, có thể mất vài giây…';
    try {
      const r = await api('POST', '/api/app/update/check', {});
      $('#setVer').textContent = verText(r.current, r.newpipe);
      showInstallBtn(!!r.newer);
      if (r.error && !r.latest) {
        out.className = 'setnote err'; out.textContent = r.error;
      } else if (r.newer) {
        out.className = 'setnote new';
        out.textContent = 'Có bản mới: ' + r.latest + '.' + (room.isOwner
          ? ' Bấm "Tải và cài bản mới" bên dưới: box tự tải, bạn chỉ bấm "Cài đặt" trên TV.'
          : ' Chỉ chủ phòng cài được.');
      } else if (r.latest) {
        out.textContent = 'Karaoke Box đang ở bản mới nhất (' + r.latest + ').' + (r.error ? ' Lần kiểm tra này lỗi: ' + r.error : '');
      } else {
        out.textContent = 'Chưa có thông tin bản mới. Thử lại sau vài giây.';
      }
    } catch (e) {
      out.className = 'setnote err'; out.textContent = e.message;
    } finally {
      checkingUpd = false; btn.disabled = false; btn.textContent = 'Kiểm tra cập nhật';
    }
  });
  // Đặt lại mặc định: bấm hai lần (lần đầu đổi nhãn, 4 giây không bấm tiếp thì thôi) để tránh bấm nhầm
  let resetArmed = false, resetTimer;
  const disarmReset = () => { resetArmed = false; clearTimeout(resetTimer); $('#setReset').classList.remove('armed'); $('#setReset').textContent = 'Đặt lại mặc định'; };
  $('#setReset').addEventListener('click', async () => {
    if (!resetArmed) {
      resetArmed = true;
      $('#setReset').classList.add('armed'); $('#setReset').textContent = 'Bấm lần nữa để xác nhận';
      resetTimer = setTimeout(disarmReset, 4000);
      return;
    }
    disarmReset();
    try { settings = await api('POST', '/api/settings/reset', {}); renderSettings(); toast('Đã đặt lại cài đặt về mặc định'); }
    catch (e) { toast(e.message); }
  });
  const openSettings = () => {
    setModal.hidden = false; loadSettings(); loadVersion(); disarmReset(); disarmInstall();
    $('#setUpd').hidden = true; instBtn.hidden = true; instState.hidden = true; backupMsg.hidden = true;
    // Bản mới đã biết từ lần kiểm tra trước (box tự kiểm tra lúc mở app) + tiến độ tải nếu đang tải dở
    api('GET', '/api/app/update/status').then((st) => {
      if (st.newer) {
        const out = $('#setUpd');
        out.hidden = false; out.className = 'setnote new'; out.textContent = 'Có bản mới: ' + st.latest + '.';
        showInstallBtn(true);
      }
      renderInstall(st);
      if (st.phase === 'DOWNLOADING') pollInstall();
    }).catch(() => {});
    // Nhật ký lỗi chỉ chủ phòng xem được; mở bằng trình duyệt không gửi được header nên gắn mã máy vào liên kết
    $('#setDiag').href = '/api/youtube/diag?cid=' + encodeURIComponent(clientId);
  };
  const closeSettings = () => { setModal.hidden = true; };
  $('#btnSettings').addEventListener('click', openSettings);
  $('#setClose').addEventListener('click', closeSettings);
  $('#setBg').addEventListener('click', closeSettings);
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !setModal.hidden) closeSettings(); });
  $('#setVocal').addEventListener('click', (e) => {
    const b = e.target.closest('.segbtn');
    if (b) saveSetting({ leftIsVocal: b.dataset.v === '1' });
  });
  $('#setFx').addEventListener('change', (e) => saveSetting({ audioFx: e.target.value }));
  $('#setFxLevel').addEventListener('input', (e) => { $('#setFxLevelVal').textContent = e.target.value + '%'; });
  $('#setFxLevel').addEventListener('change', (e) => saveSetting({ audioFxLevel: +e.target.value }));
  $('#setFair').addEventListener('change', (e) => saveSetting({ fairQueue: e.target.checked }));
  $('#setGuestSkip').addEventListener('change', (e) => saveSetting({ guestCanSkip: e.target.checked }));
  $('#setYt').addEventListener('click', (e) => {
    const b = e.target.closest('.segbtn');
    if (b) saveSetting({ youtubeMaxHeight: +b.dataset.h });
  });
  $('#setTheme').addEventListener('change', (e) => saveSetting({ stageTheme: e.target.value }));

  // ---------- Trò chơi cuối bài (vòng xoay may mắn): cài đặt riêng, chỉ chủ phòng chỉnh ----------
  let gameCfg = null;
  const gameModal = $('#gameModal');
  function setOpts(el, items) {   // chỉ dựng lại danh sách khi nó thật sự đổi (dựng lại lúc đang mở ô chọn sẽ làm ô chọn đóng)
    const sig = items.map((o) => o.key + '|' + o.label).join(';');
    if (el._sig !== sig) {
      el._sig = sig;
      el.innerHTML = items.map((o) => `<option value="${esc(String(o.key))}">${esc(o.label)}</option>`).join('');
    }
  }
  const numOpts = (list, fmt) => (list || []).map((n) => ({ key: n, label: fmt(n) }));
  function renderGame() {
    const s = gameCfg;
    if (!s) return;
    $('#gEnabled').checked = !!s.enabled;
    setOpts($('#gFreq'), s.frequencyOptions || []); $('#gFreq').value = s.frequency;
    setOpts($('#gThreshold'), numOpts(s.thresholdOptions, (n) => n + ' phút')); $('#gThreshold').value = String(s.thresholdMin);
    setOpts($('#gBefore'), s.levelBeforeOptions || []); $('#gBefore').value = s.levelBefore;
    setOpts($('#gAfter'), s.levelAfterOptions || []); $('#gAfter').value = s.levelAfter;
    setOpts($('#gIdle'), numOpts(s.idleResetOptions, (n) => n === 0 ? 'Tắt (chỉ tính từ lúc mở app)' : 'Nghỉ quá ' + n + ' phút')); $('#gIdle').value = String(s.idleResetMin);
    $('#gPos').checked = !!s.groupPosition;
    $('#gGrp').checked = !!s.groupGroup;
    $('#gMen').checked = !!s.segMen;
    $('#gWomen').checked = !!s.segWomen;
    $('#gTrait').checked = !!s.groupTrait;
    setOpts($('#gMax'), numOpts(s.maxRoundsOptions, (n) => n === 0 ? 'Không giới hạn' : n + ' vòng')); $('#gMax').value = String(s.maxRounds);
    setOpts($('#gResult'), numOpts(s.resultSecOptions, (n) => n + ' giây')); $('#gResult').value = String(s.resultSec);
    $('#gSound').checked = !!s.sound;
    $('#gRemind').checked = !!s.reminder;
    document.querySelectorAll('#gMen, #gWomen').forEach((el) => el.closest('.setrow').classList.toggle('dim', !s.groupGroup));
    $('#gStatus').textContent = 'Đêm hiện tại: đã hát ' + s.nightMinutes + ' phút, đã quay ' + s.roundsThisNight + ' vòng.' + (s.running ? ' TV đang quay.' : '');
    const own = !!room.isOwner;
    gameModal.classList.toggle('readonly', !own);
    $('#gameOwnerNote').hidden = own;
    gameModal.querySelectorAll('[data-own]').forEach((el) => { el.disabled = !own; });
  }
  async function loadGame() {
    try { gameCfg = await api('GET', '/api/game/settings'); renderGame(); } catch (e) { /* bỏ qua */ }
  }
  // Cập nhật ngay trên màn hình (không chờ mạng), lỗi thì trả về giá trị cũ
  async function saveGame(patch) {
    if (!gameCfg) return;
    const prev = gameCfg;
    gameCfg = Object.assign({}, gameCfg, patch);
    renderGame();
    try { gameCfg = await api('POST', '/api/game/settings', patch); renderGame(); }
    catch (e) { gameCfg = prev; renderGame(); toast(e.message); }
  }
  [['#gEnabled', 'enabled'], ['#gPos', 'groupPosition'], ['#gGrp', 'groupGroup'], ['#gMen', 'segMen'], ['#gWomen', 'segWomen'],
   ['#gTrait', 'groupTrait'], ['#gSound', 'sound'], ['#gRemind', 'reminder']].forEach(([id, key]) =>
    $(id).addEventListener('change', (e) => saveGame({ [key]: e.target.checked })));
  [['#gFreq', 'frequency'], ['#gBefore', 'levelBefore'], ['#gAfter', 'levelAfter']].forEach(([id, key]) =>
    $(id).addEventListener('change', (e) => saveGame({ [key]: e.target.value })));
  [['#gThreshold', 'thresholdMin'], ['#gIdle', 'idleResetMin'], ['#gMax', 'maxRounds'], ['#gResult', 'resultSec']].forEach(([id, key]) =>
    $(id).addEventListener('change', (e) => saveGame({ [key]: +e.target.value })));
  $('#gTest').addEventListener('click', () => act(async () => { await api('POST', '/api/game/spin-test', {}); toast('TV đang quay thử'); }));
  $('#gNight').addEventListener('click', () => {
    if (!confirm('Bắt đầu đêm mới? Thời gian đã hát và số vòng đã quay sẽ tính lại từ đầu.')) return;
    act(async () => { gameCfg = await api('POST', '/api/game/new-night', {}); renderGame(); toast('Đã bắt đầu đêm mới'); });
  });
  $('#gReset').addEventListener('click', () => {
    if (!confirm('Đặt lại mọi cài đặt trò chơi về mặc định (kể cả tắt trò chơi)?')) return;
    act(async () => { gameCfg = await api('POST', '/api/game/reset', {}); renderGame(); toast('Đã đặt lại cài đặt trò chơi'); });
  });
  const closeGame = () => { gameModal.hidden = true; };
  $('#btnGame').addEventListener('click', () => { gameModal.hidden = false; loadGame(); });
  $('#gameClose').addEventListener('click', closeGame);
  $('#gameBg').addEventListener('click', closeGame);
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !gameModal.hidden) closeGame(); });

  // Cảnh báo khi YouTube lỗi (mạng, bị chặn, YouTube đổi dữ liệu...)
  let ytStatus = null;
  function renderYtStatus() {
    const b = $('#ytBanner'), s = ytStatus;
    if (!s || s.health === 'OK') { b.hidden = true; syncTopHeight(); return; }
    const down = s.health === 'DOWN';
    b.hidden = false;
    b.classList.toggle('err', down);
    $('#ytBannerText').textContent = down
      ? `YouTube tạm thời không dùng được: ${s.message}. Bài trong máy vẫn hát bình thường.`
      : `YouTube đang chập chờn (${s.message}), app đang tự thử lại…`;
    syncTopHeight();
  }
  async function loadYtStatus() {
    try { ytStatus = (await api('GET', '/api/youtube/status')).status; renderYtStatus(); } catch (e) { /* bỏ qua */ }
  }
  $('#ytRetry').addEventListener('click', () => act(async () => {
    toast('Đang kiểm tra YouTube…');
    ytStatus = (await api('POST', '/api/youtube/check', {})).status;
    renderYtStatus();
    toast(ytStatus.health === 'OK' ? 'YouTube hoạt động bình thường' : 'YouTube vẫn đang lỗi');
  }));

  // Âm lượng
  let sliding = false, volTimer;
  const vol = $('#vol'), volBox = $('#volBox');
  function setVolVisual(v) {
    v = Math.max(0, Math.min(100, v));
    volBox.style.setProperty('--p', v / 100);
    // Biểu tượng cái loa đổi theo mức: 0 = tắt tiếng, dưới 50 = vừa, còn lại = to
    volBox.classList.toggle('v-off', v === 0);
    volBox.classList.toggle('v-mid', v > 0 && v < 50);
  }
  setVolVisual(+vol.value);
  // Thanh âm lượng: mặc định ẩn, bật trong Cài đặt (riêng mỗi điện thoại)
  const showVolEl = $('#setShowVol');
  const applyShowVol = (on) => { document.body.classList.toggle('novol', !on); if (typeof syncTopHeight === 'function') syncTopHeight(); };
  showVolEl.checked = store.get('kb.showVol') === '1';
  applyShowVol(showVolEl.checked);
  showVolEl.addEventListener('change', (e) => { store.set('kb.showVol', e.target.checked ? '1' : '0'); applyShowVol(e.target.checked); });
  vol.addEventListener('pointerdown', () => { sliding = true; });
  vol.addEventListener('pointerup', () => { sliding = false; });
  vol.addEventListener('input', () => {
    setVolVisual(+vol.value);
    clearTimeout(volTimer);
    volTimer = setTimeout(() => volAct(() => api('POST', '/api/volume', { level: +vol.value })), 80);
  });
  // Khách không chỉnh được âm lượng: báo lý do và trả thanh trượt về mức thật của TV
  async function volAct(fn) {
    try { await fn(); } catch (e) { toast(e.message); sliding = false; if (player) renderPlayer(); }
  }
  $('#volUp').addEventListener('click', () => volAct(() => api('POST', '/api/volume', { delta: 8 })));
  $('#volDown').addEventListener('click', () => volAct(() => api('POST', '/api/volume', { delta: -8 })));

  // ---------- WebSocket ----------
  // Điện thoại tắt màn hình / để trình duyệt ở nền thì Android đóng băng JS và có thể cắt socket. Không thể giữ socket sống,
  // nên: (1) nối lại NGAY khi mở lại, (2) tự phát hiện socket "zombie" bằng ping/pong, (3) chỉ hiện cảnh báo khi mất thật sự
  // quá vài giây, (4) trong lúc socket chưa nối lại thì lấy trạng thái bằng HTTP để màn hình luôn đúng.
  const PING_MS = 15000, PONG_WAIT_MS = 4000, BANNER_DELAY_MS = 4000, POLL_MS = 3000;
  let ws = null, retry = 0, pingTimer, pongTimer, reconnTimer, bannerTimer, pollTimer;
  let lastRx = Date.now(), connected = false, graceUntil = 0;

  function sendWs(type, payload) {
    if (ws && ws.readyState === 1) { try { ws.send(JSON.stringify({ type, ts: Date.now(), payload: payload || {} })); } catch (e) {} }
  }
  function showConnBanner(show) { $('#conn').hidden = !show; syncTopHeight(); }

  // Chỉ hiện "Mất kết nối" khi mất thật sự quá BANNER_DELAY_MS và trang đang hiển thị (sau khi vừa mở lại màn hình có thêm thời gian ân hạn)
  function scheduleBanner() {
    clearTimeout(bannerTimer);
    if (connected) return;
    const wait = Math.max(BANNER_DELAY_MS, graceUntil - Date.now());
    bannerTimer = setTimeout(() => {
      if (connected) return;
      if (document.hidden || Date.now() < graceUntil) { scheduleBanner(); return; }
      showConnBanner(true);
    }, wait);
  }
  function setConn(ok) {
    connected = ok;
    if (ok) { clearTimeout(bannerTimer); showConnBanner(false); stopPolling(); }
    else { scheduleBanner(); startPolling(); }
  }

  function applyQueue(version, items) {
    if (version < qVersion) return;
    qVersion = version; queue = items; renderQueue();
  }
  function applyPlayer(p) { player = p; recvAt = Date.now(); renderPlayer(); }

  // HTTP dự phòng: trong lúc WebSocket chưa nối lại, vẫn cập nhật bài đang hát + hàng chờ, và nếu server trả lời được thì nối lại WebSocket ngay
  function startPolling() {
    if (pollTimer) return;
    pollTimer = setInterval(pollState, POLL_MS);
    pollState();
  }
  function stopPolling() { clearInterval(pollTimer); pollTimer = null; }
  async function pollState() {
    if (document.hidden || connected) return;
    try {
      api('GET', '/api/room').then(applyRoom).catch(() => {});
      const d = await api('GET', '/api/state');
      if (connected) return;
      applyQueue(d.queueVersion, d.queue);
      applyPlayer(d.player);
      if (!ws || ws.readyState > 1) { retry = 0; connect(); }
    } catch (e) { /* server chưa tới được: để backoff của WebSocket lo */ }
  }

  function scheduleReconnect() {
    clearTimeout(reconnTimer);
    reconnTimer = setTimeout(connect, Math.min(5000, 600 * Math.pow(2, retry++)));
  }

  function connect(force) {
    clearTimeout(reconnTimer);
    if (ws && ws.readyState === 0) return;                 // đang nối
    if (ws && ws.readyState === 1 && !force) return;       // đang nối tốt
    if (ws) { const old = ws; ws = null; old.onopen = old.onclose = old.onerror = old.onmessage = null; try { old.close(); } catch (e) {} }

    let sock;
    try { sock = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws'); }
    catch (e) { setConn(false); scheduleReconnect(); return; }
    ws = sock;

    sock.onopen = () => {
      if (ws !== sock) return;
      retry = 0; lastRx = Date.now(); setConn(true);
      sendWs('hello', { clientId, name: clientName });
      loadFavs();
      loadYtStatus();
      loadSettings();
      clearInterval(pingTimer);
      pingTimer = setInterval(probe, PING_MS);
    };
    sock.onclose = () => {
      if (ws !== sock) return;
      clearInterval(pingTimer); clearTimeout(pongTimer);
      setConn(false);
      scheduleReconnect();
    };
    sock.onerror = () => { try { sock.close(); } catch (e) {} };
    sock.onmessage = (ev) => {
      if (ws !== sock) return;
      lastRx = Date.now();
      let m; try { m = JSON.parse(ev.data); } catch (e) { return; }
      const p = m.payload;
      switch (m.type) {
        case 'welcome': qVersion = -1; applyQueue(p.snapshot.queueVersion, p.snapshot.queue); applyPlayer(p.snapshot.player); break;
        case 'player.state': applyPlayer(p); break;
        case 'queue.updated': applyQueue(p.version, p.items); break;
        case 'favorites.updated': applyFavs(p.items); break;
        case 'yt.status': ytStatus = p; renderYtStatus(); break;
        case 'settings.changed': settings = p; renderSettings(); break;
        case 'game.settings': gameCfg = p; renderGame(); break;
        case 'app.update': renderInstall(p); break;
        case 'room': applyRoom(p); break;
        case 'toast': toast(p.message); break;
        case 'library.progress': {
          const b = $('#scanBanner');
          b.hidden = !p.scanning;
          if (p.scanning) b.textContent = `Đang quét thư viện… ${p.scanned} file`;
          else if (p.found > 0) loadSongs(true);
          syncTopHeight();
          break;
        }
      }
    };
  }

  // Gửi ping và đòi pong trong PONG_WAIT_MS; không có phản hồi = socket chết (zombie) → nối lại ngay
  function probe() {
    if (!ws || ws.readyState > 1) { retry = 0; connect(); return; }
    if (ws.readyState === 0) return;
    const sent = Date.now();
    sendWs('ping');
    clearTimeout(pongTimer);
    pongTimer = setTimeout(() => { if (lastRx < sent) { setConn(false); connect(true); } }, PONG_WAIT_MS);
  }

  // Mở lại màn hình / quay lại tab / có mạng lại: nối lại NGAY (bỏ qua backoff), kiểm tra socket còn sống không
  function resume() {
    if (document.hidden) return;
    graceUntil = Date.now() + BANNER_DELAY_MS;
    if (!connected) scheduleBanner();
    retry = 0;
    probe();
    if (!connected) pollState();
  }
  document.addEventListener('visibilitychange', () => { if (!document.hidden) resume(); });
  window.addEventListener('pageshow', resume);
  window.addEventListener('focus', resume);
  window.addEventListener('online', resume);
  document.addEventListener('resume', resume);   // Page Lifecycle API (Chrome): trang được rã đông

  // Đóng trang / thoát Zalo / đóng tab: báo TV biết ngay để QR sáng rõ lên (không báo khi chỉ tắt màn hình)
  let leaving = false;
  function sayBye() {
    if (leaving) return;
    leaving = true;
    sendWs('bye');
    try {
      fetch('/api/leave', {
        method: 'POST', keepalive: true,
        headers: { 'X-Client-Id': clientId, 'Content-Type': 'application/json; charset=utf-8' },
        body: '{}'
      });
    } catch (e) {}
  }
  window.addEventListener('pagehide', sayBye);
  window.addEventListener('beforeunload', sayBye);
  window.addEventListener('pageshow', () => { leaving = false; });   // quay lại từ bộ nhớ đệm trang (bfcache)

  // ---------- Khởi động ----------
  syncTopHeight();
  renderQueue();
  renderFavs();
  loadSongs(true);
  setSource(source);
  connect();
})();
