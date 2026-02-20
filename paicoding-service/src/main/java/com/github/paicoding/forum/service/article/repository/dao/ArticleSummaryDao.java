package com.github.paicoding.forum.service.article.repository.dao;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.paicoding.forum.service.article.repository.entity.ArticleSummaryDO;
import com.github.paicoding.forum.service.article.repository.mapper.ArticleSummaryMapper;
import org.springframework.stereotype.Repository;

/**
 * 文章AI总结 DAO
 *
 * @author Claude
 * @date 2026/2/20
 */
@Repository
public class ArticleSummaryDao extends ServiceImpl<ArticleSummaryMapper, ArticleSummaryDO> {

    public ArticleSummaryDO getByArticleId(Long articleId) {
        return lambdaQuery()
                .eq(ArticleSummaryDO::getArticleId, articleId)
                .one();
    }

    public boolean updateByArticleId(ArticleSummaryDO summaryDO) {
        return lambdaUpdate()
                .eq(ArticleSummaryDO::getArticleId, summaryDO.getArticleId())
                .update(summaryDO);
    }

    /**
     * 原子更新状态（CAS：仅当前状态匹配时才更新）
     *
     * @param articleId  文章ID
     * @param fromStatus 期望的当前状态
     * @param toStatus   目标状态
     * @return 是否更新成功
     */
    public boolean casUpdateStatus(Long articleId, int fromStatus, int toStatus) {
        return lambdaUpdate()
                .eq(ArticleSummaryDO::getArticleId, articleId)
                .eq(ArticleSummaryDO::getStatus, fromStatus)
                .set(ArticleSummaryDO::getStatus, toStatus)
                .update();
    }

    /**
     * 标记失败并记录原因
     */
    public boolean markFailed(Long articleId, String failReason) {
        return lambdaUpdate()
                .eq(ArticleSummaryDO::getArticleId, articleId)
                .set(ArticleSummaryDO::getStatus, -1)
                .set(ArticleSummaryDO::getFailReason, failReason)
                .update();
    }
}
