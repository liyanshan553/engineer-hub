package com.github.paicoding.forum.service.article.service.summary;

import com.github.paicoding.forum.api.model.vo.article.summary.ArticleSummaryResVo;

public interface ArticleSummaryService {
    ArticleSummaryResVo getOrGenerateSummary(Long articleId);
}
