package com.github.paicoding.forum.service.chatai.springai;

import com.github.paicoding.forum.api.model.vo.article.summary.ArticleSummaryCardDTO;
import com.github.paicoding.forum.core.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 文章总结 Agent 流编排：检索上下文 -> 结构化生成 -> 规则校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleSummaryAgentOrchestrator {
    private static final int CHUNK_SIZE = 700;
    private static final int TOP_K = 8;

    private final ChatClient.Builder chatClientBuilder;
    private final EmbeddingModel embeddingModel;

    public ArticleSummaryCardDTO run(Long articleId, String title, String content) {
        String ragContext = retrieveContext(content);
        ArticleSummaryCardDTO summary = generateStructuredSummary(title, content, ragContext);
        return sanitizeAndVerify(summary, content);
    }

    private String retrieveContext(String content) {
        if (StringUtils.isBlank(content)) {
            return "";
        }
        List<Document> docs = split(content);
        if (docs.isEmpty()) {
            return "";
        }

        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        store.add(docs);

        List<Document> result = store.similaritySearch(SearchRequest.builder()
                .query("输出TLDR、要点、适用人群、术语、步骤、风险与引用锚点")
                .topK(TOP_K)
                .build());
        if (result == null || result.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Document doc : result) {
            if (StringUtils.isNotBlank(doc.getText())) {
                sb.append(doc.getText()).append("\n");
            }
        }
        return sb.toString();
    }

    private ArticleSummaryCardDTO generateStructuredSummary(String title, String content, String ragContext) {
        String system = "你是技术文章总结助手，必须输出严格JSON，不要输出任何额外文本。";
        String user = "请基于文章生成总结卡片，字段与约束如下：\n"
                + "tldr: 1~2句结论\n"
                + "highlights: 3~6条要点（每条<=30字）\n"
                + "whoShouldRead: 适用人群/前置知识\n"
                + "keyTerms: 术语列表\n"
                + "codeOrSteps: 若有代码/步骤，输出3~5步\n"
                + "risksOrPitfalls: 坑点/注意事项\n"
                + "citations: 引用锚点(原文片段，避免杜撰)\n"
                + "输出JSON结构示例："
                + "{\"tldr\":\"\",\"highlights\":[\"\"],\"whoShouldRead\":\"\",\"keyTerms\":[\"\"],\"codeOrSteps\":[\"\"],\"risksOrPitfalls\":[\"\"],\"citations\":[\"\"]}\n"
                + "文章标题：" + title + "\n"
                + "RAG上下文：\n" + ragContext + "\n"
                + "文章原文：\n" + content;

        String json = chatClientBuilder.build()
                .prompt()
                .system(system)
                .user(user)
                .call()
                .content();

        if (StringUtils.isBlank(json)) {
            return ArticleSummaryCardDTO.empty();
        }

        try {
            return JsonUtil.toObj(json, ArticleSummaryCardDTO.class);
        } catch (Exception e) {
            log.warn("解析文章总结JSON失败，尝试修复: {}", e.getMessage());
            String repaired = chatClientBuilder.build()
                    .prompt()
                    .system("你是JSON修复器，只输出合法JSON")
                    .user("将下面文本修复为合法JSON，结构同ArticleSummaryCardDTO：\n" + json)
                    .call()
                    .content();
            try {
                return JsonUtil.toObj(repaired, ArticleSummaryCardDTO.class);
            } catch (Exception ex) {
                log.error("修复后仍解析失败", ex);
                return ArticleSummaryCardDTO.empty();
            }
        }
    }

    private ArticleSummaryCardDTO sanitizeAndVerify(ArticleSummaryCardDTO summary, String content) {
        if (summary == null) {
            return ArticleSummaryCardDTO.empty();
        }
        summary.setHighlights(limitLength(summary.getHighlights(), 6, 30));
        summary.setCodeOrSteps(limitLength(summary.getCodeOrSteps(), 5, 60));
        summary.setRisksOrPitfalls(limitLength(summary.getRisksOrPitfalls(), 6, 40));
        summary.setKeyTerms(limitLength(summary.getKeyTerms(), 12, 20));
        summary.setCitations(filterCitations(summary.getCitations(), content));
        if (summary.getHighlights().size() < 3) {
            summary.getHighlights().add("建议结合原文目录快速复盘要点");
        }
        return summary;
    }

    private List<String> limitLength(List<String> list, int maxSize, int maxLen) {
        if (list == null) {
            return new ArrayList<>();
        }
        List<String> ans = new ArrayList<>();
        for (String item : list) {
            if (StringUtils.isBlank(item)) {
                continue;
            }
            String tmp = item.trim();
            if (tmp.length() > maxLen) {
                tmp = tmp.substring(0, maxLen);
            }
            ans.add(tmp);
            if (ans.size() >= maxSize) {
                break;
            }
        }
        return ans;
    }

    private List<String> filterCitations(List<String> citations, String content) {
        if (citations == null || StringUtils.isBlank(content)) {
            return Collections.emptyList();
        }
        List<String> ans = new ArrayList<>();
        for (String c : citations) {
            if (StringUtils.isBlank(c)) {
                continue;
            }
            String s = c.trim();
            if (s.length() > 40) {
                s = s.substring(0, 40);
            }
            if (content.contains(s)) {
                ans.add(s);
            }
        }
        return ans;
    }

    private List<Document> split(String content) {
        String text = content.trim();
        if (text.isEmpty()) {
            return Collections.emptyList();
        }
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < text.length(); i += CHUNK_SIZE) {
            int end = Math.min(i + CHUNK_SIZE, text.length());
            docs.add(new Document(text.substring(i, end)));
        }
        return docs;
    }
}
