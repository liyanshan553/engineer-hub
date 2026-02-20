(function () {
    function esc(text) {
        if (!text) return '';
        return String(text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function renderList(items) {
        if (!items || items.length === 0) return '<li>暂无</li>';
        return items.map(function (x) {
            return '<li>' + esc(x) + '</li>';
        }).join('');
    }

    function renderSummary(summary) {
        return '' +
            '<div class="summary-section"><strong>tldr</strong><p>' + esc(summary.tldr) + '</p></div>' +
            '<div class="summary-section"><strong>highlights</strong><ul>' + renderList(summary.highlights) + '</ul></div>' +
            '<div class="summary-section"><strong>who_should_read</strong><p>' + esc(summary.whoShouldRead) + '</p></div>' +
            '<div class="summary-section"><strong>key_terms</strong><ul>' + renderList(summary.keyTerms) + '</ul></div>' +
            '<div class="summary-section"><strong>code_or_steps</strong><ul>' + renderList(summary.codeOrSteps) + '</ul></div>' +
            '<div class="summary-section"><strong>risks_or_pitfalls</strong><ul>' + renderList(summary.risksOrPitfalls) + '</ul></div>' +
            '<div class="summary-section"><strong>citations</strong><ul>' + renderList(summary.citations) + '</ul></div>';
    }

    function setState(state) {
        $('#summaryInitState').toggle(state === 'init');
        $('#summaryLoadingState').toggle(state === 'loading');
        $('#summaryContent').toggle(state === 'ready');
    }

    function pollSummary(articleId, retry) {
        $.get('/article/api/summary/card', {articleId: articleId}, function (res) {
            if (!res || !res.result) {
                setState('init');
                return;
            }
            var data = res.result;
            if (data.status === 'READY') {
                $('#summaryContent').html(renderSummary(data.summary || {}));
                setState('ready');
                return;
            }

            if (data.status === 'GENERATING' && retry > 0) {
                setState('loading');
                setTimeout(function () {
                    pollSummary(articleId, retry - 1);
                }, 1500);
                return;
            }

            setState('init');
        }).fail(function () {
            setState('init');
        });
    }

    $(function () {
        if (typeof articleId === 'undefined') {
            return;
        }

        $('#generateSummaryBtn').on('click', function () {
            setState('loading');
            pollSummary(articleId, 30);
        });

        // 页面打开时尝试读取缓存
        pollSummary(articleId, 0);
    });
})();
