// 文章AI总结卡片功能

$(document).ready(function () {
    var summaryLoaded = false;
    var pollTimer = null;
    var pollCount = 0;
    var pollInterval = 2000; // 初始轮询间隔 2s
    var MAX_POLL = 60;       // 最多轮询次数
    var MAX_INTERVAL = 10000; // 最大轮询间隔 10s
    var collapsed = true;     // 默认折叠状态

    // 点击"AI总结"按钮触发
    $(document).on('click', '#summaryTriggerBtn', function () {
        if (summaryLoaded) {
            toggleCollapse();
            return;
        }
        var $btn = $(this);
        $btn.prop('disabled', true).text('生成中...');
        loadSummary();
    });

    function loadSummary() {
        if (typeof articleId === 'undefined') return;

        $.ajax({
            url: '/article/api/summary/' + articleId,
            type: 'GET',
            dataType: 'json',
            timeout: 15000,
            success: function (res) {
                if (res.status && res.status.code === 0 && res.result) {
                    var data = res.result;
                    if (data.status === 1) {
                        summaryLoaded = true;
                        renderSummaryCard(data);
                        stopPolling();
                    } else if (data.status === 0) {
                        showGenerating();
                        startPolling();
                    } else if (data.status === -1) {
                        showFailed(data.failReason);
                        stopPolling();
                    }
                }
            },
            error: function (xhr, status) {
                if (status === 'timeout') {
                    startPolling();
                } else {
                    showFailed('网络请求失败');
                    stopPolling();
                }
            }
        });
    }

    // 指数退避轮询
    function startPolling() {
        if (pollTimer) return;
        schedulePoll();
    }

    function schedulePoll() {
        pollTimer = setTimeout(function () {
            pollCount++;
            if (pollCount > MAX_POLL) {
                stopPolling();
                showFailed('生成超时，请稍后重试');
                return;
            }
            loadSummary();
            pollInterval = Math.min(Math.floor(pollInterval * 1.5), MAX_INTERVAL);
            if (!summaryLoaded) {
                schedulePoll();
            }
        }, pollInterval);
    }

    function stopPolling() {
        if (pollTimer) {
            clearTimeout(pollTimer);
            pollTimer = null;
        }
        pollCount = 0;
        pollInterval = 2000;
    }

    function showGenerating() {
        $('#summaryTriggerBtn').hide();
        $('#summaryContent').html(
            '<div class="summary-generating">' +
            '<div class="spinner"></div>' +
            '<div>AI 正在分析文章，请稍候...</div>' +
            '<div class="summary-generating-hint">通常需要 10~30 秒</div>' +
            '</div>'
        );
    }

    function showFailed(reason) {
        $('#summaryTriggerBtn').hide();
        var reasonHtml = reason ? '<div class="summary-fail-reason">' + escapeHtml(reason) + '</div>' : '';
        $('#summaryContent').html(
            '<div class="summary-failed">' +
            '<div>总结生成失败</div>' +
            reasonHtml +
            '<button class="summary-retry-btn" onclick="retrySummary()">重新生成</button>' +
            '</div>'
        );
    }

    function toggleCollapse() {
        var $body = $('#summaryBody');
        var $btn = $('#summaryTriggerBtn');
        collapsed = !collapsed;
        if (collapsed) {
            $body.slideUp(200);
            $btn.find('.btn-arrow').html('&#x25BC;');
        } else {
            $body.slideDown(200);
            $btn.find('.btn-arrow').html('&#x25B2;');
        }
    }

    // 重试
    window.retrySummary = function () {
        $('#summaryContent').html('');
        $('#summaryTriggerBtn').show().prop('disabled', false).html(
            '<span class="btn-icon">&#x2728;</span> 生成总结'
        );
        summaryLoaded = false;

        $.ajax({
            url: '/article/api/summary/regenerate/' + articleId,
            type: 'POST',
            dataType: 'json',
            success: function () {
                $('#summaryTriggerBtn').click();
            },
            error: function () {
                showFailed('重新生成请求失败');
            }
        });
    };

    function renderSummaryCard(data) {
        var html = '<div id="summaryBody" class="summary-body">';

        // TLDR
        if (data.tldr) {
            html += '<div class="summary-tldr">' + escapeHtml(data.tldr) + '</div>';
        }

        // Highlights
        if (data.highlights && data.highlights.length) {
            html += '<div class="summary-section">';
            html += '<div class="summary-section-title">HIGHLIGHTS</div>';
            html += '<ul class="summary-highlights">';
            data.highlights.forEach(function (h) {
                html += '<li>' + escapeHtml(h) + '</li>';
            });
            html += '</ul></div>';
        }

        // Who should read
        if (data.whoShouldRead) {
            html += '<div class="summary-section">';
            html += '<div class="summary-section-title">WHO SHOULD READ</div>';
            html += '<p style="margin:0">' + escapeHtml(data.whoShouldRead) + '</p>';
            html += '</div>';
        }

        // Key terms
        if (data.keyTerms && data.keyTerms.length) {
            html += '<div class="summary-section">';
            html += '<div class="summary-section-title">KEY TERMS</div>';
            html += '<div class="term-tags">';
            data.keyTerms.forEach(function (t) {
                html += '<span class="term-tag" title="' + escapeAttr(t.explanation || '') + '">' +
                    escapeHtml(t.term) + '</span>';
            });
            html += '</div></div>';
        }

        // Code / Steps
        if (data.codeOrSteps && data.codeOrSteps.length) {
            html += '<div class="summary-section">';
            html += '<div class="summary-section-title">KEY STEPS</div>';
            html += '<ol class="summary-steps">';
            data.codeOrSteps.forEach(function (s) {
                html += '<li>' + escapeHtml(s) + '</li>';
            });
            html += '</ol></div>';
        }

        // Risks / Pitfalls
        if (data.risksOrPitfalls && data.risksOrPitfalls.length) {
            html += '<div class="summary-section">';
            html += '<div class="summary-section-title">RISKS & PITFALLS</div>';
            html += '<ul class="risk-list">';
            data.risksOrPitfalls.forEach(function (r) {
                html += '<li>' + escapeHtml(r) + '</li>';
            });
            html += '</ul></div>';
        }

        // Citations
        if (data.citations && data.citations.length) {
            html += '<div class="summary-section">';
            html += '<div class="summary-section-title">CITATIONS</div>';
            html += '<ul class="citation-list">';
            data.citations.forEach(function (c) {
                html += '<li data-anchor="' + escapeAttr(c.anchor || '') + '" onclick="scrollToCitation(this)">' +
                    escapeHtml(c.text) + '</li>';
            });
            html += '</ul></div>';
        }

        // AI 声明
        html += '<div class="summary-disclaimer">';
        html += 'AI 生成，仅供参考';
        if (data.modelName) {
            html += ' · ' + escapeHtml(data.modelName);
        }
        if (data.version && data.version > 1) {
            html += ' · v' + data.version;
        }
        html += '</div>';

        html += '</div>';

        $('#summaryContent').html(html);
        collapsed = false;

        // 更新按钮为折叠/展开
        $('#summaryTriggerBtn').show().prop('disabled', false).html(
            '<span class="btn-icon">&#x2728;</span> AI 总结 <span class="btn-arrow">&#x25B2;</span>'
        );
    }

    // 点击引用跳转到文章对应位置
    window.scrollToCitation = function (el) {
        var anchor = $(el).data('anchor');
        if (!anchor) return;

        var parts = String(anchor).split(':');
        if (parts.length < 1) return;

        var paragraphIndex = parseInt(parts[0]);
        if (isNaN(paragraphIndex)) return;

        var articleContentEl = document.getElementById('articleContent');
        if (!articleContentEl) return;

        var paragraphs = articleContentEl.querySelectorAll('p, h1, h2, h3, h4, h5, h6, li, pre, blockquote');
        if (paragraphIndex >= paragraphs.length) {
            paragraphIndex = Math.max(0, paragraphs.length - 1);
        }

        var target = paragraphs[paragraphIndex];
        if (!target) return;

        target.scrollIntoView({behavior: 'smooth', block: 'center'});
        target.classList.add('citation-highlight');
        setTimeout(function () {
            target.classList.remove('citation-highlight');
        }, 2500);
    };

    function escapeHtml(text) {
        if (!text) return '';
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(text));
        return div.innerHTML;
    }

    function escapeAttr(text) {
        return escapeHtml(text).replace(/"/g, '&quot;');
    }
});
