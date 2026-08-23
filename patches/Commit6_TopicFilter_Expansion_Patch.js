// =========================================================================================
// COMMIT 6 — Mở rộng TOPIC_KEYWORD_FILTER_SCRIPT: bắt "Gợi ý cho bạn", Reels đề xuất, Group đề xuất
// Thay thế TOÀN BỘ hằng số TOPIC_KEYWORD_FILTER_SCRIPT hiện có trong NobookWV.kt
// =========================================================================================

private const val TOPIC_KEYWORD_FILTER_SCRIPT = """
(function () {
    try {
        if (window.__nobookTopicFilterActive) return;
        window.__nobookTopicFilterActive = true;

        function getActiveKeywords() {
            try {
                if (window.NobookFeaturesBridge && window.NobookFeaturesBridge.getSavedKeywords) {
                    return JSON.parse(window.NobookFeaturesBridge.getSavedKeywords() || '[]');
                }
            } catch (e) {}
            return [];
        }

        var normalize = function (text) {
            return (text || '').toLowerCase();
        };

        var matchesKeyword = function (text, keywords) {
            if (!keywords || keywords.length === 0) return false;
            var norm = normalize(text);
            return keywords.some(function (kw) {
                return kw && norm.indexOf(kw.toLowerCase()) !== -1;
            });
        };

        var SUGGESTED_LABEL_HINTS = [
            'gợi ý cho bạn', 'suggested for you', 'chủ đề liên quan', 'related topics',
            'bài viết bạn có thể thích', 'tham gia nhóm', 'suggested group', 'suggested groups',
            'more from', 'people you may know', 'reels được đề xuất', 'suggested reels'
        ];

        var isSuggestedLabel = function (text) {
            var norm = normalize(text);
            return SUGGESTED_LABEL_HINTS.some(function (hint) { return norm.indexOf(hint) !== -1; });
        };

        var CONTAINER_SELECTORS = [
            'div[role="article"]',
            'div[data-pagelet^="FeedUnit"]',
            'div[data-pagelet*="Browse"]',
            'div[data-sigil*="feed-story"]',
            'div.story_body_container',
            'div[aria-label*="reel" i]',
            'div[data-pagelet*="GroupSuggestion"]',
            'div[data-pagelet*="Suggestion"]'
        ];

        function collectFullText(container) {
            var parts = [container.innerText || ''];
            var labelNodes = container.querySelectorAll('[aria-label], span, h3, h4');
            for (var i = 0; i < labelNodes.length; i++) {
                var n = labelNodes[i];
                var al = n.getAttribute ? (n.getAttribute('aria-label') || '') : '';
                if (al) parts.push(al);
            }
            return parts.join(' \n ');
        }

        var filterFeed = function () {
            var keywords = getActiveKeywords();

            CONTAINER_SELECTORS.forEach(function (selector) {
                var nodes = document.querySelectorAll(selector);
                nodes.forEach(function (post) {
                    if (post.hasAttribute('data-nobook-keyword-filtered')) return;

                    var fullText = collectFullText(post);
                    var hitsUserKeyword = matchesKeyword(fullText, keywords);
                    var hitsSuggestedLabel = isSuggestedLabel(fullText) && matchesKeyword(fullText, keywords);

                    if (hitsUserKeyword || hitsSuggestedLabel) {
                        post.style.setProperty('display', 'none', 'important');
                        post.setAttribute('data-nobook-keyword-filtered', '1');
                    }
                });
            });
        };

        var ric = window.requestIdleCallback || function (cb) {
            return setTimeout(function () { cb({ timeRemaining: function () { return 1; }, didTimeout: false }); }, 1);
        };
        var pending = false;
        var scheduleFilter = function () {
            if (pending) return;
            pending = true;
            ric(function () {
                pending = false;
                filterFeed();
            }, { timeout: 1000 });
        };

        scheduleFilter();
        var observer = new MutationObserver(function () { scheduleFilter(); });
        observer.observe(document.body, { childList: true, subtree: true });

        window.__nobookRefilterTopics = scheduleFilter;

        console.info('[Nobook] Dynamic Topic keyword filter active (mở rộng: suggested/related/reels/group)');
    } catch (err) {
        console.error('[Nobook] Topic keyword filter injection failed:', err);
    }
})();
"""

// HƯỚNG DẪN TÍCH HỢP:
// 1. Thay TOÀN BỘ hằng số TOPIC_KEYWORD_FILTER_SCRIPT hiện có bằng khối ở trên.
// 2. Không cần sửa dòng gọi navigator.evaluateJavaScript(TOPIC_KEYWORD_FILTER_SCRIPT) {} — tên hằng số không đổi.
// 3. Hành vi mới: vẫn ẩn bài viết thường nếu khớp từ khóa (giữ nguyên), CỘNG THÊM ẩn khối
//    gợi ý/Reels/Group đề xuất NẾU nội dung bên trong khớp từ khóa. Không ẩn cứng mọi khối gợi ý
//    mặc định để tránh phá trải nghiệm Reels/Group hợp lệ không liên quan.
