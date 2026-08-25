// =========================================================================================
// COMMIT 7 (v3) — Fix lỗi stale-session: reset bộ đếm nếu phát hiện gap lớn giữa 2 lần tick
// (ví dụ người dùng tắt hản app nhiều giờ/ngày rồi mở lại) — tránh popup bật sai ngay khi mở app.
// Thay thế TOÀN BỘ hằng số STEALTH_FB_TIMER_SCRIPT v2 bằng bản v3 này.
// =========================================================================================

private const val STEALTH_FB_TIMER_SCRIPT = """
(function () {
  try {
    if (window.__nobookStealthTimerActive) return;
    window.__nobookStealthTimerActive = true;

    var CYCLE_SECONDS = 30 * 60;
    var WARNING_LEAD_SECONDS = 2 * 60;
    var STALE_GAP_SECONDS = 5 * 60;
    var BADGE_ID = 'nobook-fb-timer-badge';
    var POPUP_ID = 'nobook-fb-timer-popup';
    var USERNAME_CACHE_KEY = 'nobook_cached_fb_username';
    var START_TS_KEY = 'nobook_fb_timer_start_ts';
    var LAST_TICK_KEY = 'nobook_fb_timer_last_tick_ts';

    function extractNameFromProfileMenu() {
      var candidates = [];

      var navProfileLinks = document.querySelectorAll(
        'a[href*="/me/"] span, a[aria-label][href*="profile.php"] span, ' +
        'div[aria-label="Your profile"] span, div[aria-label="Trang cá nhân của bạn"] span'
      );
      navProfileLinks.forEach(function (el) { candidates.push(el.textContent); });

      var menuNameNodes = document.querySelectorAll(
        'div[role="button"][aria-label*="account" i] span, ' +
        'div[role="button"][aria-label*="tài khoản" i] span, ' +
        'a[href*="/me/"] strong, a[href*="profile.php?id="] strong'
      );
      menuNameNodes.forEach(function (el) { candidates.push(el.textContent); });

      var hovercardNameNodes = document.querySelectorAll('a[data-hovercard] strong, a[data-hovercard] span');
      hovercardNameNodes.forEach(function (el) { candidates.push(el.textContent); });

      for (var i = 0; i < candidates.length; i++) {
        var raw = (candidates[i] || '').trim();
        if (raw && raw.length >= 2 && raw.length <= 60 && !/^[0-9]+$/.test(raw)) {
          return raw;
        }
      }
      return '';
    }

    function getAccountName() {
      var fromMenu = extractNameFromProfileMenu();
      if (fromMenu) {
        try { localStorage.setItem(USERNAME_CACHE_KEY, fromMenu); } catch (e) {}
        return fromMenu;
      }
      try {
        return localStorage.getItem(USERNAME_CACHE_KEY) || '';
      } catch (e) { return ''; }
    }

    function resolveSessionStart() {
      var now = Date.now();
      var start = parseInt(localStorage.getItem(START_TS_KEY) || '0', 10);
      var lastTick = parseInt(localStorage.getItem(LAST_TICK_KEY) || '0', 10);

      var isStale = !start || !lastTick || ((now - lastTick) / 1000) > STALE_GAP_SECONDS;
      if (isStale) {
        start = now;
        try {
          localStorage.setItem(START_TS_KEY, String(start));
          localStorage.setItem(LAST_TICK_KEY, String(now));
        } catch (e) {}
      }
      return start;
    }

    function getElapsedSeconds() {
      var start = resolveSessionStart();
      var now = Date.now();
      try { localStorage.setItem(LAST_TICK_KEY, String(now)); } catch (e) {}
      return Math.floor((now - start) / 1000);
    }

    function removeBadge() {
      var el = document.getElementById(BADGE_ID);
      if (el) el.remove();
    }

    function showBadge(secondsLeft) {
      var el = document.getElementById(BADGE_ID);
      if (!el) {
        el = document.createElement('div');
        el.id = BADGE_ID;
        el.style.cssText =
          'position:fixed;top:8px;right:8px;min-width:10px;height:10px;' +
          'padding:2px 6px;border-radius:10px;background:rgba(220,38,38,0.85);' +
          'color:#fff;font-size:10px;font-weight:700;z-index:999997;' +
          'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;' +
          'box-shadow:0 2px 6px rgba(0,0,0,0.35);pointer-events:none;';
        document.body.appendChild(el);
      }
      var mins = Math.floor(secondsLeft / 60);
      var secs = secondsLeft % 60;
      el.textContent = mins + ':' + (secs < 10 ? '0' : '') + secs;
    }

    function closeReminderPopup() {
      var el = document.getElementById(POPUP_ID);
      if (el) el.remove();
    }

    function showReminderPopup(elapsedMinutes) {
      closeReminderPopup();
      var name = getAccountName();
      var greeting = name
        ? ('Nghỉ ngơi mắt nhé, ' + name + '!')
        : 'Nghỉ ngơi mắt nhé, bạn!';

      var overlay = document.createElement('div');
      overlay.id = POPUP_ID;
      overlay.style.cssText =
        'position:fixed;inset:0;background:rgba(0,0,0,0.6);z-index:1000002;' +
        'display:flex;align-items:center;justify-content:center;';
      overlay.addEventListener('click', function (e) { if (e.target === overlay) closeReminderPopup(); });

      var box = document.createElement('div');
      box.style.cssText =
        'background:#1c1c1e;color:#fff;border-radius:16px;padding:24px 22px;' +
        'max-width:300px;width:85%;text-align:center;box-shadow:0 8px 28px rgba(0,0,0,0.5);' +
        'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;';

      var icon = document.createElement('div');
      icon.textContent = '⏳';
      icon.style.cssText = 'font-size:40px;margin-bottom:12px;';

      var msg = document.createElement('div');
      msg.textContent = greeting;
      msg.style.cssText = 'font-size:16px;font-weight:700;margin-bottom:10px;';

      var sub = document.createElement('div');
      sub.style.cssText = 'font-size:13px;color:#c9c9cc;margin-bottom:20px;line-height:1.5;';
      sub.appendChild(document.createTextNode('Bạn đã lướt Facebook liên tục '));
      var boldMinutes = document.createElement('strong');
      boldMinutes.textContent = elapsedMinutes + ' phút';
      sub.appendChild(boldMinutes);
      sub.appendChild(document.createTextNode('.'));

      var btn = document.createElement('button');
      btn.textContent = 'Đã hiểu';
      btn.style.cssText =
        'width:100%;background:#2563eb;color:#fff;border:none;border-radius:12px;' +
        'padding:13px;font-weight:700;font-size:14px;cursor:pointer;';
      btn.addEventListener('click', closeReminderPopup);

      box.appendChild(icon);
      box.appendChild(msg);
      box.appendChild(sub);
      box.appendChild(btn);
      overlay.appendChild(box);
      document.body.appendChild(overlay);
    }

    var lastNotifiedCycle = -1;

    function tick() {
      var elapsed = getElapsedSeconds();
      var cycleIndex = Math.floor(elapsed / CYCLE_SECONDS);
      var secondsIntoCycle = elapsed % CYCLE_SECONDS;
      var secondsUntilMark = CYCLE_SECONDS - secondsIntoCycle;

      if (secondsUntilMark <= WARNING_LEAD_SECONDS && secondsUntilMark > 0) {
        showBadge(secondsUntilMark);
      } else {
        removeBadge();
      }

      if (secondsIntoCycle === 0 && cycleIndex > lastNotifiedCycle && elapsed >= 2) {
        lastNotifiedCycle = cycleIndex;
        removeBadge();
        var elapsedMinutes = Math.round(elapsed / 60);
        showReminderPopup(elapsedMinutes);
      }
    }

    tick();
    setInterval(tick, 1000);

    console.info('[Nobook] Stealth FB usage timer active (v3: stale-session guard + tên từ Profile menu)');
  } catch (err) {
    console.error('[Nobook] Stealth FB timer injection failed:', err);
  }
})();
"""
