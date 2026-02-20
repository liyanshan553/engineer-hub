package com.github.paicoding.forum.service.article.summary;

import com.github.paicoding.forum.api.model.vo.article.dto.ArticleSummaryDTO;
import com.github.paicoding.forum.core.util.JsonUtil;
import com.github.paicoding.forum.service.article.repository.entity.ArticleDO;
import com.github.paicoding.forum.service.article.service.ArticleReadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文章总结 Agent 编排服务（ReAct 模式）
 *
 * <p>Agent 状态机流程:
 * <pre>
 * ANALYZE → PLAN → GENERATE → VALIDATE ──→ DONE
 *                      ↑          │
 *                      └── REFINE ┘ (最多 MAX_REFINE_ROUNDS 次)
 * </pre>
 *
 * <p>相比 v1 线性管道的核心改进:
 * <ul>
 *   <li>ANALYZE: 规则分析文章特征，驱动后续决策路由</li>
 *   <li>PLAN: LLM 阅读文章后输出结构化分析计划</li>
 *   <li>GENERATE: 根据计划自适应 Prompt（长文分段/短文直接）</li>
 *   <li>VALIDATE: 多维度校验 + 引用模糊匹配</li>
 *   <li>REFINE: 针对具体问题的自纠错回路</li>
 *   <li>移除无意义的 RAG（总结场景已有全文，无需自检索）</li>
 * </ul>
 *
 * @author Claude
 * @date 2026/2/20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleSummaryAgentService {

    /** 文章最大处理长度 */
    private static final int MAX_ARTICLE_LENGTH = 30000;

    /** 自纠错最大轮次 */
    private static final int MAX_REFINE_ROUNDS = 2;

    /** 代码块正则 */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```[\\s\\S]*?```");

    /** 步骤关键词 */
    private static final List<String> STEP_KEYWORDS = Arrays.asList(
            "第一步", "第二步", "步骤", "Step", "step",
            "首先", "然后", "接着", "最后", "1.", "2.", "3."
    );

    private final ChatClient.Builder chatClientBuilder;
    private final ArticleReadService articleReadService;

    // ====================================================================
    // 公共入口
    // ====================================================================

    /**
     * Agent 主入口：生成文章总结
     *
     * <p>以状态机驱动，依次经历 ANALYZE → PLAN → GENERATE → VALIDATE → (REFINE) → DONE</p>
     */
    public ArticleSummaryDTO generateSummary(Long articleId) {
        AgentState state = AgentState.ANALYZE;
        ArticleProfile profile = null;
        String plan = null;
        ArticleSummaryDTO dto = null;
        ValidationResult validation = null;
        int refineRound = 0;

        while (state != AgentState.DONE) {
            log.info("文章总结Agent [{}] articleId={}", state.getDesc(), articleId);

            switch (state) {
                case ANALYZE:
                    profile = doAnalyze(articleId);
                    state = AgentState.PLAN;
                    break;

                case PLAN:
                    plan = doPlan(profile);
                    state = AgentState.GENERATE;
                    break;

                case GENERATE:
                    dto = doGenerate(profile, plan);
                    state = AgentState.VALIDATE;
                    break;

                case VALIDATE:
                    validation = doValidate(dto, profile);
                    if (validation.isPassed()) {
                        state = AgentState.DONE;
                    } else if (refineRound < MAX_REFINE_ROUNDS) {
                        state = AgentState.REFINE;
                    } else {
                        // 超出最大修复轮次，接受当前结果
                        log.warn("文章总结Agent - 达到最大修复轮次{}，接受当前结果, articleId={}",
                                MAX_REFINE_ROUNDS, articleId);
                        state = AgentState.DONE;
                    }
                    break;

                case REFINE:
                    refineRound++;
                    log.info("文章总结Agent - REFINE 第{}轮, issues={}, articleId={}",
                            refineRound, validation.getIssues(), articleId);
                    dto = doRefine(dto, profile, validation);
                    state = AgentState.VALIDATE;
                    break;

                default:
                    state = AgentState.DONE;
            }
        }

        dto.setArticleId(articleId);
        dto.setStatus(1);
        log.info("文章总结Agent - 完成! refineRounds={}, articleId={}", refineRound, articleId);
        return dto;
    }

    // ====================================================================
    // ANALYZE: 规则分析文章特征
    // ====================================================================

    private ArticleProfile doAnalyze(Long articleId) {
        String articleContent = articleReadService.queryArticleContentForAI(articleId);
        ArticleDO article = articleReadService.queryBasicArticle(articleId);

        if (StringUtils.isBlank(articleContent) || article == null) {
            throw new IllegalArgumentException("文章不存在或内容为空, articleId=" + articleId);
        }

        // 超长截断
        if (articleContent.length() > MAX_ARTICLE_LENGTH) {
            articleContent = articleContent.substring(0, MAX_ARTICLE_LENGTH);
        }

        ArticleProfile profile = new ArticleProfile();
        profile.setArticleId(articleId);
        profile.setTitle(article.getTitle());
        profile.setContent(articleContent);

        // 段落切分
        List<String> paragraphs = Arrays.stream(articleContent.split("\n"))
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
        profile.setParagraphs(paragraphs);
        profile.setParagraphCount(paragraphs.size());

        // 长度等级
        int len = articleContent.length();
        if (len <= 2000) {
            profile.setLengthLevel(ArticleProfile.LengthLevel.SHORT);
        } else if (len <= 8000) {
            profile.setLengthLevel(ArticleProfile.LengthLevel.MEDIUM);
        } else {
            profile.setLengthLevel(ArticleProfile.LengthLevel.LONG);
        }

        // 代码块检测
        profile.setHasCodeBlocks(CODE_BLOCK_PATTERN.matcher(articleContent).find());

        // 文章类型推断
        if (profile.isHasCodeBlocks() || containsStepKeywords(articleContent)) {
            profile.setArticleType(ArticleProfile.ArticleType.TUTORIAL);
        } else if (containsAnalysisKeywords(articleContent)) {
            profile.setArticleType(ArticleProfile.ArticleType.ANALYSIS);
        } else {
            profile.setArticleType(ArticleProfile.ArticleType.GENERAL);
        }

        log.info("文章特征: length={}, level={}, type={}, hasCode={}, paragraphs={}",
                len, profile.getLengthLevel(), profile.getArticleType(),
                profile.isHasCodeBlocks(), profile.getParagraphCount());
        return profile;
    }

    private boolean containsStepKeywords(String content) {
        return STEP_KEYWORDS.stream().anyMatch(content::contains);
    }

    private boolean containsAnalysisKeywords(String content) {
        return content.contains("原理") || content.contains("架构") || content.contains("设计")
                || content.contains("分析") || content.contains("对比") || content.contains("源码");
    }

    // ====================================================================
    // PLAN: LLM 阅读文章生成分析计划
    // ====================================================================

    private String doPlan(ArticleProfile profile) {
        // 短文章不需要 PLAN 阶段，直接返回默认计划
        if (profile.getLengthLevel() == ArticleProfile.LengthLevel.SHORT) {
            log.info("短文章跳过 PLAN 阶段，使用默认计划");
            return buildDefaultPlan(profile);
        }

        String planPrompt = "你是一个专业的技术文章分析师。请阅读以下文章，输出一个简要的分析计划。\n\n" +
                "文章标题: " + profile.getTitle() + "\n" +
                "文章长度: " + profile.getContent().length() + "字\n" +
                "文章类型: " + (profile.isHasCodeBlocks() ? "含代码的技术教程" : "概念分析文章") + "\n\n" +
                "请用以下 JSON 格式返回分析计划（纯 JSON，无 markdown 包裹）：\n" +
                "{\n" +
                "  \"focus\": \"这篇文章的核心主题（一句话）\",\n" +
                "  \"key_sections\": [\"值得重点分析的章节或主题\"],\n" +
                "  \"should_extract_steps\": true/false,\n" +
                "  \"should_extract_risks\": true/false,\n" +
                "  \"summary_strategy\": \"direct|hierarchical\"\n" +
                "}\n\n" +
                "规则：\n" +
                "- should_extract_steps: 仅当文章含有步骤说明或代码示例时为 true\n" +
                "- should_extract_risks: 仅当文章涉及常见错误、坑点或注意事项时为 true\n" +
                "- summary_strategy: 文章>5000字用 hierarchical，否则用 direct\n\n" +
                "文章内容（前3000字）：\n" +
                truncate(profile.getContent(), 3000);

        try {
            String response = callLlm(planPrompt);
            if (StringUtils.isNotBlank(response)) {
                log.info("PLAN 结果: {}", truncate(response, 200));
                return response;
            }
        } catch (Exception e) {
            log.warn("PLAN 阶段 LLM 调用失败，降级为默认计划", e);
        }
        return buildDefaultPlan(profile);
    }

    private String buildDefaultPlan(ArticleProfile profile) {
        return "{\"focus\":\"" + profile.getTitle() + "\"," +
                "\"key_sections\":[]," +
                "\"should_extract_steps\":" + profile.needsCodeSteps() + "," +
                "\"should_extract_risks\":" + (profile.getArticleType() == ArticleProfile.ArticleType.TUTORIAL) + "," +
                "\"summary_strategy\":\"" + (profile.needsChunkedSummary() ? "hierarchical" : "direct") + "\"}";
    }

    // ====================================================================
    // GENERATE: 自适应 Prompt 生成结构化 JSON
    // ====================================================================

    private ArticleSummaryDTO doGenerate(ArticleProfile profile, String plan) {
        String systemPrompt = buildAdaptiveSystemPrompt(profile, plan);
        String userPrompt = buildAdaptiveUserPrompt(profile);

        String jsonResponse = callLlm(systemPrompt, userPrompt);

        if (StringUtils.isBlank(jsonResponse)) {
            throw new RuntimeException("大模型返回空内容, articleId=" + profile.getArticleId());
        }

        return parseJson(jsonResponse, profile.getArticleId());
    }

    private String buildAdaptiveSystemPrompt(ArticleProfile profile, String plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业的技术文章分析师。请根据用户提供的技术文章，生成一份结构化的总结卡片。\n");
        sb.append("严格按照以下JSON格式返回，不要添加任何markdown标记或其他文字，只返回纯JSON：\n");
        sb.append("{\n");
        sb.append("  \"tldr\": \"1~2句话总结文章核心结论\",\n");
        sb.append("  \"highlights\": [\"要点1（不超过30字）\", \"要点2\", ...],\n");
        sb.append("  \"who_should_read\": \"适用人群描述和所需前置知识\",\n");
        sb.append("  \"key_terms\": [{\"term\": \"术语\", \"explanation\": \"简短解释\"}],\n");

        // 根据文章特征决定是否要求 code_or_steps
        if (profile.needsCodeSteps()) {
            sb.append("  \"code_or_steps\": [\"步骤1\", \"步骤2\", ...],\n");
        } else {
            sb.append("  \"code_or_steps\": [],\n");
        }

        // 根据文章类型决定是否要求 risks_or_pitfalls
        if (profile.getArticleType() == ArticleProfile.ArticleType.TUTORIAL) {
            sb.append("  \"risks_or_pitfalls\": [\"注意事项1\", ...],\n");
        } else {
            sb.append("  \"risks_or_pitfalls\": [],\n");
        }

        sb.append("  \"citations\": [{\"text\": \"引用的原文片段(10~30字)\", \"paragraph_index\": 段落序号}]\n");
        sb.append("}\n\n");

        sb.append("约束规则：\n");
        sb.append("- highlights 3~6条，每条不超过30个中文字符\n");
        sb.append("- citations 中的 text 必须是文章中【逐字出现】的原文片段，不可修改任何标点或空格\n");
        sb.append("- key_terms 最多8个\n");

        if (profile.needsCodeSteps()) {
            sb.append("- code_or_steps 提取文章中的核心操作步骤，3~5步\n");
        }
        if (profile.getArticleType() == ArticleProfile.ArticleType.TUTORIAL) {
            sb.append("- risks_or_pitfalls 提取文章中提到的常见错误或注意事项\n");
        }

        sb.append("- 所有内容使用中文\n");

        // 注入 PLAN 阶段的分析计划作为上下文
        if (StringUtils.isNotBlank(plan)) {
            sb.append("\n以下是对这篇文章的预分析计划，请参考：\n").append(plan).append("\n");
        }

        return sb.toString();
    }

    private String buildAdaptiveUserPrompt(ArticleProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 文章标题\n").append(profile.getTitle()).append("\n\n");

        if (profile.needsChunkedSummary()) {
            // 长文章：分段摘要策略
            sb.append("# 文章全文（分段展示）\n");
            List<String> paragraphs = profile.getParagraphs();
            int sectionSize = Math.max(1, paragraphs.size() / 4);
            for (int i = 0; i < paragraphs.size(); i++) {
                if (i % sectionSize == 0) {
                    sb.append("\n--- 第").append((i / sectionSize) + 1).append("部分 ---\n");
                }
                sb.append(paragraphs.get(i)).append("\n");
            }
        } else {
            sb.append("# 文章全文\n").append(profile.getContent()).append("\n");
        }

        sb.append("\n请生成总结卡片JSON。注意 citations 中的 text 必须是文章中原封不动出现的片段。");
        return sb.toString();
    }

    // ====================================================================
    // VALIDATE: 多维度校验
    // ====================================================================

    private ValidationResult doValidate(ArticleSummaryDTO dto, ArticleProfile profile) {
        ValidationResult result = ValidationResult.pass();

        // 1. 基本字段完整性
        if (StringUtils.isBlank(dto.getTldr())) {
            result.addIssue("tldr 为空");
            result.setTldrNeedsRefine(true);
        } else if (dto.getTldr().length() > 200) {
            result.addIssue("tldr 过长（" + dto.getTldr().length() + "字），应在1~2句内");
            result.setTldrNeedsRefine(true);
        }

        // 2. highlights 数量校验
        if (dto.getHighlights() == null || dto.getHighlights().size() < 3) {
            result.addIssue("highlights 少于3条");
            result.setHighlightsNeedRefine(true);
        }

        // 3. 引用锚点校验（核心：模糊匹配 + 移除伪造引用）
        int removedCount = validateAndEnrichCitations(dto, profile);
        if (removedCount > 0) {
            result.setRemovedCitations(removedCount);
            // 如果超过一半的引用被移除，需要重新生成
            int originalCount = (dto.getCitations() != null ? dto.getCitations().size() : 0) + removedCount;
            if (originalCount > 0 && removedCount > originalCount / 2) {
                result.addIssue("超过半数引用(" + removedCount + "/" + originalCount + ")在原文中不存在");
                result.setCitationsNeedRefine(true);
            }
        }

        // 4. 条件字段一致性
        if (profile.needsCodeSteps()
                && (dto.getCodeOrSteps() == null || dto.getCodeOrSteps().isEmpty())) {
            // 文章有代码但未提取步骤 — 这是软性问题，不触发 refine
            log.info("文章含代码块但未提取到步骤，可接受");
        }

        if (result.isPassed()) {
            log.info("校验通过");
        } else {
            log.info("校验发现 {} 个问题: {}", result.getIssues().size(), result.getIssues());
        }
        return result;
    }

    /**
     * 引用校验与锚点生成（支持模糊匹配）
     *
     * <p>改进点：v1 仅做 String.indexOf 精确匹配，本版增加：
     * 1. 精确匹配优先
     * 2. 去标点/空格后的模糊匹配（容忍 LLM 微调标点）
     * 3. 匹配失败则移除</p>
     *
     * @return 被移除的引用数量
     */
    private int validateAndEnrichCitations(ArticleSummaryDTO dto, ArticleProfile profile) {
        if (dto.getCitations() == null || dto.getCitations().isEmpty()) {
            return 0;
        }

        String content = profile.getContent();
        List<String> paragraphs = profile.getParagraphs();
        // 去标点版本，用于模糊匹配
        String normalizedContent = normalizePunctuation(content);

        int removedCount = 0;
        Iterator<ArticleSummaryDTO.CitationDTO> it = dto.getCitations().iterator();

        while (it.hasNext()) {
            ArticleSummaryDTO.CitationDTO citation = it.next();
            if (StringUtils.isBlank(citation.getText())) {
                it.remove();
                removedCount++;
                continue;
            }

            // 策略1：精确匹配
            int exactIdx = content.indexOf(citation.getText());
            if (exactIdx >= 0) {
                citation.setAnchor(buildAnchor(paragraphs, citation.getText(), true));
                continue;
            }

            // 策略2：去标点模糊匹配
            String normalizedCitation = normalizePunctuation(citation.getText());
            int fuzzyIdx = normalizedContent.indexOf(normalizedCitation);
            if (fuzzyIdx >= 0 && normalizedCitation.length() >= 5) {
                // 尝试回溯到原文中对应的段落
                citation.setAnchor(buildAnchorFuzzy(paragraphs, normalizedCitation));
                log.debug("引用模糊匹配成功: {}", citation.getText());
                continue;
            }

            // 匹配失败 → 移除
            log.warn("引用文本在原文中不存在，已移除: {}", citation.getText());
            it.remove();
            removedCount++;
        }
        return removedCount;
    }

    // ====================================================================
    // REFINE: 针对校验问题的自纠错
    // ====================================================================

    private ArticleSummaryDTO doRefine(ArticleSummaryDTO current, ArticleProfile profile,
                                       ValidationResult validation) {
        StringBuilder refinePrompt = new StringBuilder();
        refinePrompt.append("你之前为一篇文章生成了总结卡片，但存在以下问题需要修复：\n\n");

        for (String issue : validation.getIssues()) {
            refinePrompt.append("- ").append(issue).append("\n");
        }

        refinePrompt.append("\n当前总结卡片内容：\n");
        refinePrompt.append(JsonUtil.toStr(current)).append("\n\n");

        refinePrompt.append("文章标题: ").append(profile.getTitle()).append("\n");

        // 只附加需要修复的字段对应的上下文
        if (validation.isCitationsNeedRefine()) {
            refinePrompt.append("\n请特别注意：citations 的 text 字段必须是文章中【逐字出现】的原文片段。\n");
            refinePrompt.append("以下是文章前2000字供你参考：\n");
            refinePrompt.append(truncate(profile.getContent(), 2000)).append("\n");
        }

        if (validation.isTldrNeedsRefine()) {
            refinePrompt.append("\n请重新生成 tldr，控制在1~2句话内。\n");
        }

        if (validation.isHighlightsNeedRefine()) {
            refinePrompt.append("\n请重新生成 highlights，确保有3~6条要点。\n");
        }

        refinePrompt.append("\n请输出修复后的完整 JSON（纯 JSON，无 markdown 包裹）。");

        try {
            String response = callLlm(refinePrompt.toString());
            if (StringUtils.isNotBlank(response)) {
                ArticleSummaryDTO refined = parseJson(response, profile.getArticleId());
                // 保留未修复字段的旧值
                return mergeRefined(current, refined, validation);
            }
        } catch (Exception e) {
            log.warn("REFINE 阶段失败，保留当前结果", e);
        }
        return current;
    }

    /**
     * 合并修复结果：只更新有问题的字段，保留正常字段
     */
    private ArticleSummaryDTO mergeRefined(ArticleSummaryDTO current, ArticleSummaryDTO refined,
                                            ValidationResult validation) {
        if (validation.isTldrNeedsRefine() && StringUtils.isNotBlank(refined.getTldr())) {
            current.setTldr(refined.getTldr());
        }
        if (validation.isHighlightsNeedRefine()
                && refined.getHighlights() != null && !refined.getHighlights().isEmpty()) {
            current.setHighlights(refined.getHighlights());
        }
        if (validation.isCitationsNeedRefine()
                && refined.getCitations() != null && !refined.getCitations().isEmpty()) {
            current.setCitations(refined.getCitations());
        }
        // 其他字段：如果 refined 有值且 current 为空，也补充
        if (StringUtils.isBlank(current.getWhoShouldRead()) && StringUtils.isNotBlank(refined.getWhoShouldRead())) {
            current.setWhoShouldRead(refined.getWhoShouldRead());
        }
        if ((current.getKeyTerms() == null || current.getKeyTerms().isEmpty())
                && refined.getKeyTerms() != null && !refined.getKeyTerms().isEmpty()) {
            current.setKeyTerms(refined.getKeyTerms());
        }
        return current;
    }

    // ====================================================================
    // LLM 调用工具方法
    // ====================================================================

    /**
     * 单 prompt 调用（用于 PLAN / REFINE）
     */
    private String callLlm(String prompt) {
        return chatClientBuilder.build()
                .prompt()
                .user(prompt)
                .call()
                .content();
    }

    /**
     * system + user 双 prompt 调用（用于 GENERATE）
     */
    private String callLlm(String systemPrompt, String userPrompt) {
        return chatClientBuilder.build()
                .prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }

    // ====================================================================
    // JSON 解析
    // ====================================================================

    private ArticleSummaryDTO parseJson(String jsonResponse, Long articleId) {
        String json = stripMarkdownWrapper(jsonResponse);

        ArticleSummaryRawDTO raw;
        try {
            raw = JsonUtil.toObj(json, ArticleSummaryRawDTO.class);
        } catch (Exception e) {
            throw new RuntimeException("JSON解析失败, articleId=" + articleId
                    + ", error=" + e.getMessage() + ", response=" + truncate(jsonResponse, 200), e);
        }

        if (raw == null) {
            throw new RuntimeException("JSON解析结果为null, articleId=" + articleId);
        }

        ArticleSummaryDTO dto = new ArticleSummaryDTO();
        dto.setArticleId(articleId);
        dto.setTldr(StringUtils.defaultString(raw.getTldr(), ""));
        dto.setWhoShouldRead(StringUtils.defaultString(raw.getWhoShouldRead(), ""));
        dto.setStatus(1);

        // highlights: 截断超长项，上限6条
        dto.setHighlights(sanitizeHighlights(raw.getHighlights()));

        // key_terms: 上限8个
        if (raw.getKeyTerms() != null && raw.getKeyTerms().size() > 8) {
            dto.setKeyTerms(new ArrayList<>(raw.getKeyTerms().subList(0, 8)));
        } else {
            dto.setKeyTerms(raw.getKeyTerms() != null ? raw.getKeyTerms() : Collections.emptyList());
        }

        dto.setCodeOrSteps(raw.getCodeOrSteps() != null ? raw.getCodeOrSteps() : Collections.emptyList());
        dto.setRisksOrPitfalls(raw.getRisksOrPitfalls() != null ? raw.getRisksOrPitfalls() : Collections.emptyList());

        // citations: 转换格式，anchor 留空等 VALIDATE 阶段填充
        if (raw.getCitations() != null) {
            List<ArticleSummaryDTO.CitationDTO> citations = new ArrayList<>();
            for (ArticleSummaryRawDTO.RawCitationDTO rc : raw.getCitations()) {
                if (StringUtils.isNotBlank(rc.getText())) {
                    ArticleSummaryDTO.CitationDTO c = new ArticleSummaryDTO.CitationDTO();
                    c.setText(rc.getText());
                    c.setAnchor("");
                    citations.add(c);
                }
            }
            dto.setCitations(citations);
        } else {
            dto.setCitations(Collections.emptyList());
        }

        return dto;
    }

    // ====================================================================
    // 工具方法
    // ====================================================================

    private String stripMarkdownWrapper(String response) {
        String json = response.trim();
        if (json.startsWith("```json")) {
            json = json.substring(7);
        } else if (json.startsWith("```")) {
            json = json.substring(3);
        }
        if (json.endsWith("```")) {
            json = json.substring(0, json.length() - 3);
        }
        return json.trim();
    }

    private List<String> sanitizeHighlights(List<String> highlights) {
        if (highlights == null || highlights.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String h : highlights) {
            if (result.size() >= 6) {
                break;
            }
            if (StringUtils.isNotBlank(h)) {
                result.add(h.length() > 30 ? h.substring(0, 30) : h);
            }
        }
        return result;
    }

    /**
     * 构建精确匹配的锚点
     */
    private String buildAnchor(List<String> paragraphs, String text, boolean exact) {
        for (int i = 0; i < paragraphs.size(); i++) {
            if (paragraphs.get(i).contains(text)) {
                int startOffset = paragraphs.get(i).indexOf(text);
                return i + ":" + startOffset + ":" + (startOffset + text.length());
            }
        }
        return "0:0:0";
    }

    /**
     * 模糊匹配的锚点：基于 normalized 段落寻找最佳匹配段落
     */
    private String buildAnchorFuzzy(List<String> paragraphs, String normalizedText) {
        for (int i = 0; i < paragraphs.size(); i++) {
            String normalizedPara = normalizePunctuation(paragraphs.get(i));
            if (normalizedPara.contains(normalizedText)) {
                return i + ":0:0";
            }
        }
        return "0:0:0";
    }

    /**
     * 去除标点和空白，用于模糊匹配
     */
    private String normalizePunctuation(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s\\p{Punct}，。、；：""''！？（）【】《》…—]", "");
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
