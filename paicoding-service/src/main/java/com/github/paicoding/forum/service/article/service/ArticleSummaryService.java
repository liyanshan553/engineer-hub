package com.github.paicoding.forum.service.article.service;

import com.github.paicoding.forum.api.model.vo.article.dto.ArticleSummaryDTO;

/**
 * 文章AI总结服务接口
 *
 * @author Claude
 * @date 2026/2/20
 */
public interface ArticleSummaryService {

    /**
     * 查询文章总结（缓存优先，无缓存则触发异步生成）
     *
     * @param articleId 文章ID
     * @return 总结DTO（status=0 表示生成中，需前端轮询）
     */
    ArticleSummaryDTO querySummary(Long articleId);

    /**
     * 重新生成总结（文章更新后调用）
     *
     * @param articleId 文章ID
     */
    void regenerate(Long articleId);
}
