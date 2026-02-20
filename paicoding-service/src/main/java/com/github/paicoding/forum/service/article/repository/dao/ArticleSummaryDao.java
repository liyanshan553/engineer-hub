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

    /**
     * 根据文章ID查询总结
     */
    public ArticleSummaryDO getByArticleId(Long articleId) {
        return lambdaQuery()
                .eq(ArticleSummaryDO::getArticleId, articleId)
                .one();
    }

    /**
     * 根据文章ID更新总结内容
     */
    public boolean updateByArticleId(ArticleSummaryDO summaryDO) {
        return lambdaUpdate()
                .eq(ArticleSummaryDO::getArticleId, summaryDO.getArticleId())
                .update(summaryDO);
    }
}
