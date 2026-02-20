package com.github.paicoding.forum.service.article.repository.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.github.paicoding.forum.api.model.entity.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文章AI总结
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("article_ai_summary")
public class ArticleAiSummaryDO extends BaseDO {
    private Long articleId;
    private String summaryJson;
    private String model;
    private String promptVersion;
    private String contentHash;
    private Integer status;
    private String errorMsg;
    private Integer deleted;
}
