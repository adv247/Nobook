// =========================================================================================
// COMMIT 5 — Thay prompt()/confirm() bằng Custom Modal cho Bookmark (fix lỗi Edit bị chặn)
// =========================================================================================
// NGUYÊN NHÂN GỐC: Android WebChromeClient KHÔNG override onJsPrompt()/onJsConfirm() trong
// NobookWV.kt hiện tại — nên window.prompt() trả về null NGAY LẬP TỨC, khiến editBookmark
// và nb-add-bookmark luôn thất bại thầm lặng.
// GIẢI PHÁP: loại bỏ hoàn toàn prompt()/confirm() ở luồng bookmark, thay bằng modal HTML/CSS.
// =========================================================================================

var BOOKMARK_MODAL_ID = 'nobook-bookmark-edit-modal';

function closeBookmarkEditModal() {
    var el = document.getElementById(BOOKMARK_MODAL_ID);
    if (el) el.remove();
}

function showBookmarkEditModal(defaultTitle, defaultUrl, onSave) {
    closeBookmarkEditModal();

    var overlay = document.createElement('div');
    overlay.id = BOOKMARK_MODAL_ID;
    overlay.style.cssText =
        'position:fixed;inset:0;background:rgba(0,0,0,0.65);z-index:1000001;' +
        'display:flex;align-items:center;justify-content:center;';
    overlay.addEventListener('click', function (e) { if (e.target === overlay) closeBookmarkEditModal(); });

    var box = document.createElement('div');
    box.style.cssText =
        'background:#1c1c1e;color:#fff;border-radius:14px;padding:18px;' +
        'max-width:340px;width:88%;box-shadow:0 8px 24px rgba(0,0,0,0.5);' +
        'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;';

    var title = document.createElement('div');
    title.textContent = 'Chỉnh sửa Bookmark';
    title.style.cssText = 'font-size:15px;font-weight:600;margin-bottom:14px;';

    var titleInput = document.createElement('input');
    titleInput.type = 'text';
    titleInput.value = defaultTitle || '';
    titleInput.placeholder = 'Tiêu đề';
    titleInput.style.cssText =
        'width:100%;box-sizing:border-box;background:#12141a;color:#fff;' +
        'border:1px solid #3c404d;border-radius:8px;padding:9px 12px;font-size:13px;margin-bottom:8px;';

    var urlInput = document.createElement('input');
    urlInput.type = 'text';
    urlInput.value = defaultUrl || '';
    urlInput.placeholder = 'Đường dẫn (URL)';
    urlInput.style.cssText =
        'width:100%;box-sizing:border-box;background:#12141a;color:#fff;' +
        'border:1px solid #3c404d;border-radius:8px;padding:9px 12px;font-size:13px;margin-bottom:14px;';

    var btnRow = document.createElement('div');
    btnRow.style.cssText = 'display:flex;gap:8px;';

    var cancelBtn = document.createElement('button');
    cancelBtn.textContent = 'Hủy';
    cancelBtn.style.cssText =
        'flex:1;background:#3a3a3c;color:#fff;border:none;border-radius:8px;' +
        'padding:10px;font-weight:600;font-size:13px;cursor:pointer;';
    cancelBtn.addEventListener('click', closeBookmarkEditModal);

    var saveBtn = document.createElement('button');
    saveBtn.textContent = 'Lưu';
    saveBtn.style.cssText =
        'flex:1;background:#2563eb;color:#fff;border:none;border-radius:8px;' +
        'padding:10px;font-weight:600;font-size:13px;cursor:pointer;';
    saveBtn.addEventListener('click', function () {
        var newTitle = titleInput.value.trim();
        var newUrl = urlInput.value.trim();
        if (!newUrl) { alert('Vui lòng nhập URL!'); return; }
        closeBookmarkEditModal();
        onSave(newTitle || newUrl, newUrl);
    });

    btnRow.appendChild(cancelBtn);
    btnRow.appendChild(saveBtn);
    box.appendChild(title);
    box.appendChild(titleInput);
    box.appendChild(urlInput);
    box.appendChild(btnRow);
    overlay.appendChild(box);
    document.body.appendChild(overlay);

    titleInput.focus();
}

function showConfirmModal(message, onConfirm, onCancel) {
    closeBookmarkEditModal();

    var overlay = document.createElement('div');
    overlay.id = BOOKMARK_MODAL_ID;
    overlay.style.cssText =
        'position:fixed;inset:0;background:rgba(0,0,0,0.65);z-index:1000001;' +
        'display:flex;align-items:center;justify-content:center;';

    var box = document.createElement('div');
    box.style.cssText =
        'background:#1c1c1e;color:#fff;border-radius:14px;padding:18px;' +
        'max-width:300px;width:85%;box-shadow:0 8px 24px rgba(0,0,0,0.5);' +
        'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;';

    var msg = document.createElement('div');
    msg.textContent = message;
    msg.style.cssText = 'font-size:14px;margin-bottom:16px;line-height:1.4;';

    var btnRow = document.createElement('div');
    btnRow.style.cssText = 'display:flex;gap:8px;';

    var noBtn = document.createElement('button');
    noBtn.textContent = 'Không';
    noBtn.style.cssText =
        'flex:1;background:#3a3a3c;color:#fff;border:none;border-radius:8px;' +
        'padding:10px;font-weight:600;font-size:13px;cursor:pointer;';
    noBtn.addEventListener('click', function () {
        closeBookmarkEditModal();
        if (onCancel) onCancel();
    });

    var yesBtn = document.createElement('button');
    yesBtn.textContent = 'Đồng ý';
    yesBtn.style.cssText =
        'flex:1;background:#2563eb;color:#fff;border:none;border-radius:8px;' +
        'padding:10px;font-weight:600;font-size:13px;cursor:pointer;';
    yesBtn.addEventListener('click', function () {
        closeBookmarkEditModal();
        onConfirm();
    });

    btnRow.appendChild(noBtn);
    btnRow.appendChild(yesBtn);
    box.appendChild(msg);
    box.appendChild(btnRow);
    overlay.appendChild(box);
    document.body.appendChild(overlay);
}

window.openBookmark = function (idx) {
    var item = bmList[idx];
    if (!item) return;
    var isFb = item.url.indexOf('facebook.com') !== -1 || item.url.indexOf('fb.com') !== -1;
    if (isFb) {
        window.navigateSocial(item.url);
    } else {
        showConfirmModal(
            'Mở link này trong trình duyệt ngoài?',
            function () {
                if (window.NobookFeaturesBridge) window.NobookFeaturesBridge.openExternalUrl(item.url);
            },
            function () {
                window.navigateSocial(item.url);
            }
        );
    }
};

window.editBookmark = function (idx) {
    var item = bmList[idx];
    if (!item) return;
    showBookmarkEditModal(item.title, item.url, function (newTitle, newUrl) {
        item.title = newTitle;
        item.url = newUrl;
        if (window.NobookFeaturesBridge) window.NobookFeaturesBridge.saveBookmarks(JSON.stringify(bmList));
        renderTab('bookmarks');
    });
};

document.getElementById('nb-add-bookmark').onclick = function () {
    showBookmarkEditModal(document.title || 'Bài viết FB', window.location.href, function (newTitle, newUrl) {
        bmList.unshift({ title: newTitle, url: newUrl, time: Date.now() });
        if (window.NobookFeaturesBridge) window.NobookFeaturesBridge.saveBookmarks(JSON.stringify(bmList));
        renderTab('bookmarks');
    });
};

// HƯỚNG DẪN TÍCH HỢP:
// 1. Chèn 3 hàm showBookmarkEditModal/closeBookmarkEditModal/showConfirmModal vào đầu
//    khối renderTab('bookmarks') trong ASSISTIVE_TOUCH_AND_AI_SCRIPT.
// 2. Thay window.openBookmark, window.editBookmark, nb-add-bookmark.onclick bằng bản ở trên.
// 3. Không cần sửa Kotlin/WebChromeClient. window.deleteBookmark và nb-btn-save-custom-bm giữ nguyên.
