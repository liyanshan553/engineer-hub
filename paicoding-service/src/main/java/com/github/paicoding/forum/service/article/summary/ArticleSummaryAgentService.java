package com.github.paicoding.forum.service.article.summary;

import com.github.paicoding.forum.api.model.vo.article.dto.ArticleSummaryDTO;
import com.github.paicoding.forum.core.util.JsonUtil;
import com.github.paicoding.forum.service.article.repository.entity.ArticleDO;
import com.github.paicoding.forum.service.article.service.ArticleReadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 基于 Spring AI 的文章总结 Agent 编排服务
 *
 * <p>Agent 步骤:
 * 1. 获取文章全文
 * 2. RAG 向量切分 + 检索关键片段
 * 3. 构建 Prompt 调用大模型，生成结构化 JSON
 * 4. 解析校验 JSON
 * 5. 引用锚点后处理（防止"瞎编"）
 * </p>
 *
 * @author Claude
 * @date 2026/2/20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleSummaryAgentService {

    private static final int RAG_CHUNK_SIZE = 800;
    private static final int RAG_TOP_K = 6;
    private static final int MAX_ARTICLE_LENGTH = 30000;

    private final ChatClient.Builder chatClientBuilder;
    private final EmbeddingModel embeddingModel;
    private final ArticleReadService articleReadService;

    /**
     * Agent 主入口：生成文章总结
     */
    public ArticleSummaryDTO generateSummary(Long articleId) {
        // ========== Step 1: 获取文章内容 ==========
        log.info("文章总结Agent - Step1: 获取文章内容, articleId={}", articleId);
        String articleContent = articleReadService.queryArticleContentForAI(articleId);
        ArticleDO article = articleReadService.queryBasicArticle(articleId);

        if (StringUtils.isBlank(articleContent) || article == null) {
            throw new IllegalArgumentException("文章不存在或内容为空, articleId=" + articleId);
        }

        // 超长文章截断
        if (articleContent.length() > MAX_ARTICLE_LENGTH) {
            articleContent = articleContent.substring(0, MAX_ARTICLE_LENGTH);
        }

        // ========== Step 2: RAG 向量切分 + 检索关键片段 ==========
        log.info("文章总结Agent - Step2: RAG向量检索, articleId={}", articleId);
        List<Document> coreFragments = ragRetrieve(articleContent, article.getTitle());

        // ========== Step 3: 构建 Prompt + 调用大模型 ==========
        log.info("文章总结Agent - Step3: 调用大模型生成总结, articleId={}", articleId);
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(article.getTitle(), articleContent, coreFragments);

        String jsonResponse = chatClientBuilder.build()
                .prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

        if (StringUtils.isBlank(jsonResponse)) {
            throw new RuntimeException("大模型返回空内容, articleId=" + articleId);
        }

        // ========== Step 4: 解析 + 校验 JSON ==========
        log.info("文章总结Agent - Step4: 解析校验JSON, articleId={}", articleId);
        ArticleSummaryDTO dto = parseAndValidate(jsonResponse, articleId);

        // ========== Step 5: 引用锚点后处理 ==========
        log.info("文章总结Agent - Step5: 引用锚点匹配, articleId={}", articleId);
        enrichCitations(dto, articleContent);

        log.info("文章总结Agent - 完成! articleId={}", articleId);
        return dto;
    }

    /**
     * RAG 检索核心片段
     */
    private List<Document> ragRetrieve(String articleContent, String title) {
        List<Document> docs = splitDocuments(articleContent);
        if (docs.isEmpty()) {
            return Collections.emptyList();
        }

        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        vectorStore.add(docs);

        List<Document> result = vectorStore.similaritySearch(
                SearchRequest.builder().query(title).topK(RAG_TOP_K).build());
        return result != null ? result : Collections.emptyList();
    }

    /**
     * 文档切分
     */
    private List<Document> splitDocuments(String content) {
        if (StringUtils.isBlank(content)) {
            return Collections.emptyList();
        }
        content = content.trim();
        int len = content.length();
        if (len <= RAG_CHUNK_SIZE) {
            return Collections.singletonList(new Document(content));
        }
        List<Document> docs = new ArrayList<>((len / RAG_CHUNK_SIZE) + 1);
        for (int i = 0; i < len; i += RAG_CHUNK_SIZE) {
            int end = Math.min(i + RAG_CHUNK_SIZE, len);
            docs.add(new Document(content.substring(i, end)));
        }
        return docs;
    }

    private String buildSystemPrompt() {
        return "你是一个专业的技术文章分析师。请根据用户提供的技术文章，生成一份结构化的总结卡片。\n" +
                "严格按照以下JSON格式返回，不要添加任何markdown标记或其他文字，只返回纯JSON：\n" +
                "{\n" +
                "  \"tldr\": \"1~2句话总结文章核心结论\",\n" +
                "  \"highlights\": [\"要点1（不超过30字）\", \"要点2\", ...],\n" +
                "  \"who_should_read\": \"适用人群描述和所需前置知识\",\n" +
                "  \"key_terms\": [{\"term\": \"术语\", \"explanation\": \"简短解释\"}],\n" +
                "  \"code_or_steps\": [\"步骤1\", \"步骤2\", ...],\n" +
                "  \"risks_or_pitfalls\": [\"注意事项1\", ...],\n" +
                "  \"citations\": [{\"text\": \"引用的原文片段(10~30字)\", \"paragraph_index\": 段落序号}]\n" +
                "}\n\n" +
                "约束规则：\n" +
                "- highlights 3~6条，每条不超过30个中文字符\n" +
                "- citations 中的 text 必须是文章中原文出现的片段，不可编造\n" +
                "- key_terms 最多8个\n" +
                "- code_or_steps 仅在文章包含代码示例或步骤说明时填写，3~5步；如无则返回空数组\n" +
                "- risks_or_pitfalls 如无则返回空数组\n" +
                "- 所有内容使用中文";
    }

    private String buildUserPrompt(String title, String articleContent, List<Document> coreFragments) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 文章标题\n").append(title).append("\n\n");
        sb.append("# 文章全文\n").append(articleContent).append("\n\n");

        if (coreFragments != null && !coreFragments.isEmpty()) {
            sb.append("# 核心片段（RAG检索结果）\n");
            for (Document doc : coreFragments) {
                if (StringUtils.isNotBlank(doc.getText())) {
                    sb.append("- ").append(doc.getText()).append("\n");
                }
            }
        }

        sb.append("\n请生成总结卡片JSON。");
        return sb.toString();
    }

    /**
     * 解析大模型返回的 JSON 并校验字段
     */
    private ArticleSummaryDTO parseAndValidate(String jsonResponse, Long articleId) {
        // 去掉可能的 markdown 代码块包裹
        String json = jsonResponse.trim();
        if (json.startsWith("```json")) {
            json = json.substring(7);
        } else if (json.startsWith("```")) {
            json = json.substring(3);
        }
        if (json.endsWith("```")) {
            json = json.substring(0, json.length() - 3);
        }
        json = json.trim();

        ArticleSummaryRawDTO raw = JsonUtil.toObj(json, ArticleSummaryRawDTO.class);
        if (raw == null) {
            throw new RuntimeException("JSON解析失败, articleId=" + articleId + ", response=" + jsonResponse);
        }

        ArticleSummaryDTO dto = new ArticleSummaryDTO();
        dto.setArticleId(articleId);
        dto.setTldr(StringUtils.defaultString(raw.getTldr(), ""));
        dto.setWhoShouldRead(StringUtils.defaultString(raw.getWhoShouldRead(), ""));
        dto.setStatus(1);

        // highlights: 校验 3~6 条，截断超长项
        List<String> highlights = raw.getHighlights();
        if (highlights != null) {
            List<String> validated = new ArrayList<>();
            for (String h : highlights) {
                if (validated.size() >= 6) break;
                if (StringUtils.isNotBlank(h)) {
                    validated.add(h.length() > 30 ? h.substring(0, 30) : h);
                }
            }
            dto.setHighlights(validated);
        } else {
            dto.setHighlights(Collections.emptyList());
        }

        // key_terms: 最多 8 个
        if (raw.getKeyTerms() != null && raw.getKeyTerms().size() > 8) {
            dto.setKeyTerms(raw.getKeyTerms().subList(0, 8));
        } else {
            dto.setKeyTerms(raw.getKeyTerms() != null ? raw.getKeyTerms() : Collections.emptyList());
        }

        dto.setCodeOrSteps(raw.getCodeOrSteps() != null ? raw.getCodeOrSteps() : Collections.emptyList());
        dto.setRisksOrPitfalls(raw.getRisksOrPitfalls() != null ? raw.getRisksOrPitfalls() : Collections.emptyList());

        // citations: 转换格式
        if (raw.getCitations() != null) {
            List<ArticleSummaryDTO.CitationDTO> citations = new ArrayList<>();
            for (ArticleSummaryRawDTO.RawCitationDTO rc : raw.getCitations()) {
                if (StringUtils.isNotBlank(rc.getText())) {
                    ArticleSummaryDTO.CitationDTO c = new ArticleSummaryDTO.CitationDTO();
                    c.setText(rc.getText());
                    // anchor 后续在 enrichCitations 中填充
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

    /**
     * 引用锚点后处理：校验引用文本是否真实存在于原文中，不存在则移除
     */
    private void enrichCitations(ArticleSummaryDTO dto, String articleContent) {
        if (dto.getCitations() == null || dto.getCitations().isEmpty()) {
            return;
        }

        String[] paragraphs = articleContent.split("\n");
        Iterator<ArticleSummaryDTO.CitationDTO> it = dto.getCitations().iterator();

        while (it.hasNext()) {
            ArticleSummaryDTO.CitationDTO citation = it.next();
            // 在原文中查找引用文本
            int idx = articleContent.indexOf(citation.getText());
            if (idx < 0) {
                // 原文中找不到该引用 → 移除（防止瞎编）
                log.warn("引用文本在原文中不存在，已移除: {}", citation.getText());
                it.remove();
                continue;
            }

            // 计算所在段落索引，构建锚点
            int paragraphIndex = findParagraphIndex(paragraphs, citation.getText());
            int startOffset = paragraphs[paragraphIndex].indexOf(citation.getText());
            int endOffset = startOffset + citation.getText().length();
            citation.setAnchor(paragraphIndex + ":" + startOffset + ":" + endOffset);
        }
    }

    private int findParagraphIndex(String[] paragraphs, String text) {
        for (int i = 0; i < paragraphs.length; i++) {
            if (paragraphs[i].contains(text)) {
                return i;
            }
        }
        return 0;
    }
}
