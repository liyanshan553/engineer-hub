package com.github.paicoding.forum.service.chatai.springai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 基于 Spring AI 的评论机器人统一调用入口。
 *
 * <p>对于带有上下文材料（如文章正文）的问答场景，采用轻量 RAG：
 * 1. 将上下文切分后写入临时向量库
 * 2. 基于用户问题执行相似检索
 * 3. 把检索片段拼装为系统上下文再进行对话</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpringAiBotService {
    private static final int RAG_TOP_K = 4;

    private final ChatClient.Builder chatClientBuilder;
    private final EmbeddingModel embeddingModel;

    public void ask(String sourceBizId, String systemPrompt, String userQuestion, String ragContext, Consumer<String> consumer) {
        String finalSystemPrompt = buildRagSystemPrompt(sourceBizId, systemPrompt, userQuestion, ragContext);

        ChatClient.CallResponseSpec spec = chatClientBuilder.build()
                .prompt()
                .system(finalSystemPrompt)
                .user(userQuestion)
                .call();

        String content = spec.content();
        if (StringUtils.isNotBlank(content)) {
            consumer.accept(content.trim());
        } else {
            log.warn("spring-ai 评论机器人返回空内容, sourceBizId={}", sourceBizId);
        }
    }

    private String buildRagSystemPrompt(String sourceBizId, String systemPrompt, String userQuestion, String ragContext) {
        if (StringUtils.isBlank(ragContext)) {
            return systemPrompt;
        }

        List<Document> docs = splitDocuments(ragContext, sourceBizId);
        if (docs.isEmpty()) {
            return systemPrompt;
        }

        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        vectorStore.add(docs);

        List<Document> searchResult = vectorStore.similaritySearch(SearchRequest.builder()
                .query(userQuestion)
                .topK(RAG_TOP_K)
                .build());
        if (searchResult == null || searchResult.isEmpty()) {
            return systemPrompt;
        }

        StringBuilder ref = new StringBuilder();
        for (Document doc : searchResult) {
            if (StringUtils.isBlank(doc.getText())) {
                continue;
            }
            ref.append("- ").append(doc.getText()).append('\n');
        }

        if (ref.length() == 0) {
            return systemPrompt;
        }

        return systemPrompt + "\n\n请优先基于以下检索到的参考片段回答（RAG上下文）：\n" + ref;
    }

    private List<Document> splitDocuments(String ragContext, String sourceBizId) {
        if (StringUtils.isBlank(ragContext)) {
            return Collections.emptyList();
        }

        final int chunkSize = 600;
        String content = ragContext.trim();
        int len = content.length();
        if (len <= chunkSize) {
            return Collections.singletonList(new Document(content));
        }

        java.util.ArrayList<Document> docs = new java.util.ArrayList<>((len / chunkSize) + 1);
        for (int i = 0; i < len; i += chunkSize) {
            int end = Math.min(i + chunkSize, len);
            String chunk = content.substring(i, end);
            docs.add(new Document(chunk));
        }
        log.debug("RAG文档切分完成 sourceBizId={}, chunkCnt={}", sourceBizId, docs.size());
        return docs;
    }
}
