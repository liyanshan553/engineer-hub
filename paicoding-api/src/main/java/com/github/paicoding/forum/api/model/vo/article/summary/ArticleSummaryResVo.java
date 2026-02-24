package com.github.paicoding.forum.api.model.vo.article.summary;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ArticleSummaryResVo {
    private Long articleId;
    private ArticleSummaryStatusEnum status;
    private ArticleSummaryCardDTO summary;
    private String message;
    private Long updatedAt;
}
