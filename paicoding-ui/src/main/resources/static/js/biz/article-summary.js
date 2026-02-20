// 文章AI总结卡片功能

$(document).ready(function () {
    var summaryLoaded = false;
    var pollTimer = null;
    var pollCount = 0;
    var MAX_POLL = 40; // 最多轮询40次(约120秒)

    // 点击"AI总结"按钮触发
    $(document).on('click', '#summaryTriggerBtn', function () {
        if (summaryLoaded) return;
        var $btn = $(this);
        $btn.prop('disabled', true);
        loadSummary();
    });

    function loadSummary() {
        $.ajax({
            url: '/article/api/summary/' + articleId,
            type: 'GET',
            dataType: 'json',
            success: function (res) {
                if (res.status && res.status.code === 0 && res.result) {
                    var data = res.result;
                    if (data.status === 1) {
                        // 生成完成
                        summaryLoaded = true;
                        renderSummaryCard(data);
                        stopPolling();
                    } else if (data.status === 0) {
                        // 生成中
                        showGenerating();
                        startPolling();
                    } else if (data.status === -1) {
                        // 失败
                        showFailed();
                        stopPolling();
                    }
                }
            },
            error: function () {
                showFailed();
                stopPolling();
            }
        });
    }

    function startPolling() {
        if (pollTimer) return;
        pollTimer = setInterval(function () {
            pollCount++;
            if (pollCount > MAX_POLL) {
                stopPolling();
                showFailed();
                return;
            }
            loadSummary();
        }, 3000);
    }

    function stopPolling() {
        if (pollTimer) {
            clearInterval(pollTimer);
            pollTimer = null;
        }
        pollCount = 0;
    }

    function showGenerating() {
        $('#summaryTriggerBtn').hide();
        $('#summaryContent').html(
            '<div class="summary-generating">' +
            '<div class="spinner"></div>' +
            '<div>AI 正在分析文章，请稍候...</div>' +
            '</div>'
        );
    }

    function showFailed() {
        $('#summaryTriggerBtn').hide();
        $('#summaryContent').html(
            '<div class="summary-failed">' +
            '<div>总结生成失败</div>' +
            '<button class="summary-retry-btn" onclick="retrySummary()">重新生成</button>' +
            '</div>'
        );
    }

    // 重试
    window.retrySummary = function () {
        $('#summaryContent').html('');
        $('#summaryTriggerBtn').show().prop('disabled', false);
        summaryLoaded = false;

        $.ajax({
            url: '/article/api/summary/regenerate/' + articleId,
            type: 'POST',
            dataType: 'json',
            success: function () {
                $('#summaryTriggerBtn').click();
            }
        });
    };

    function renderSummaryCard(data) {
        var html = '';

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

        $('#summaryContent').html(html);
    }

    // 点击引用跳转到文章对应位置
    window.scrollToCitation = function (el) {
        var anchor = $(el).data('anchor');
        if (!anchor) return;

        var parts = anchor.split(':');
        if (parts.length < 3) return;

        var paragraphIndex = parseInt(parts[0]);
        var articleContentEl = document.getElementById('articleContent');
        if (!articleContentEl) return;

        // 获取所有段落级元素
        var paragraphs = articleContentEl.querySelectorAll('p, h1, h2, h3, h4, h5, h6, li, pre, blockquote');
        if (paragraphIndex < paragraphs.length) {
            var target = paragraphs[paragraphIndex];
            target.scrollIntoView({behavior: 'smooth', block: 'center'});
            // 短暂高亮
            target.style.transition = 'background-color 0.3s';
            target.style.backgroundColor = '#fff3cd';
            setTimeout(function () {
                target.style.backgroundColor = '';
            }, 2000);
        }
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
