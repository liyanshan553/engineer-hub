package com.github.paicoding.forum.service.article.repository.dao;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.paicoding.forum.api.model.enums.YesOrNoEnum;
import com.github.paicoding.forum.service.article.repository.entity.ArticleAiSummaryDO;
import com.github.paicoding.forum.service.article.repository.mapper.ArticleAiSummaryMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ArticleAiSummaryDao extends ServiceImpl<ArticleAiSummaryMapper, ArticleAiSummaryDO> {

    public ArticleAiSummaryDO getByArticleId(Long articleId) {
        LambdaQueryWrapper<ArticleAiSummaryDO> query = Wrappers.lambdaQuery();
        query.eq(ArticleAiSummaryDO::getArticleId, articleId)
                .eq(ArticleAiSummaryDO::getDeleted, YesOrNoEnum.NO.getCode())
                .last("limit 1");
        return baseMapper.selectOne(query);
    }

    public void saveOrUpdateByArticleId(ArticleAiSummaryDO data) {
        ArticleAiSummaryDO exist = getByArticleId(data.getArticleId());
        if (exist == null) {
            save(data);
            return;
        }

        data.setId(exist.getId());
        updateById(data);
    }
}
