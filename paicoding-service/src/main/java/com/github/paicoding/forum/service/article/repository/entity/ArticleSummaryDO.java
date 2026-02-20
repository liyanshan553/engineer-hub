package com.github.paicoding.forum.service.article.repository.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.github.paicoding.forum.api.model.entity.BaseDO;
import com.github.paicoding.forum.api.model.vo.article.dto.ArticleSummaryDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 文章AI总结实体
 *
 * @author Claude
 * @date 2026/2/20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "article_summary", autoResultMap = true)
public class ArticleSummaryDO extends BaseDO {

    private Long articleId;

    private String tldr;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> highlights;

    private String whoShouldRead;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<ArticleSummaryDTO.KeyTermDTO> keyTerms;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> codeOrSteps;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> risksOrPitfalls;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<ArticleSummaryDTO.CitationDTO> citations;

    /** 生成所用模型名称 */
    private String modelName;

    /** 失败原因 */
    private String failReason;

    private Integer version;

    /**
     * 0=生成中, 1=完成, -1=失败
     */
    private Integer status;
}
