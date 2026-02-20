package com.github.paicoding.forum.service.article.summary;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.github.paicoding.forum.api.model.vo.article.dto.ArticleSummaryDTO;
import lombok.Data;

import java.util.List;

/**
 * 大模型返回的原始 JSON 结构（用于反序列化，字段名为下划线风格）
 *
 * @author Claude
 * @date 2026/2/20
 */
@Data
public class ArticleSummaryRawDTO {
    private String tldr;

    private List<String> highlights;

    @JsonAlias({"who_should_read", "whoShouldRead"})
    private String whoShouldRead;

    @JsonAlias({"key_terms", "keyTerms"})
    private List<ArticleSummaryDTO.KeyTermDTO> keyTerms;

    @JsonAlias({"code_or_steps", "codeOrSteps"})
    private List<String> codeOrSteps;

    @JsonAlias({"risks_or_pitfalls", "risksOrPitfalls"})
    private List<String> risksOrPitfalls;

    private List<RawCitationDTO> citations;

    @Data
    public static class RawCitationDTO {
        private String text;

        @JsonAlias({"paragraph_index", "paragraphIndex"})
        private Integer paragraphIndex;
    }
}
