package com.github.paicoding.forum.service.chatai.springai;

import com.github.paicoding.forum.core.cache.RedisClient;
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
import java.util.stream.Collectors;
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
    private static final int MAX_HISTORY_ITEMS = 200;
    private static final long HISTORY_EXPIRE_SECONDS = 7 * 24 * 3600L;

    private final ChatClient.Builder chatClientBuilder;
    private final EmbeddingModel embeddingModel;

    public void ask(String sourceBizId, Long fromUserId, Long botUserId, String systemPrompt,
                    String userQuestion, String ragContext, Consumer<String> consumer) {
        String finalSystemPrompt = buildRagSystemPrompt(sourceBizId, fromUserId, botUserId, systemPrompt, userQuestion, ragContext);

        ChatClient.CallResponseSpec spec = chatClientBuilder.build()
                .prompt()
                .system(finalSystemPrompt)
                .user(userQuestion)
                .call();

        String content = spec.content();
        if (StringUtils.isNotBlank(content)) {
            String answer = content.trim();
            consumer.accept(answer);
            saveConversation(sourceBizId, fromUserId, botUserId, userQuestion, answer);
        } else {
            log.warn("spring-ai 评论机器人返回空内容, sourceBizId={}", sourceBizId);
        }
    }

    private String buildRagSystemPrompt(String sourceBizId, Long fromUserId, Long botUserId, String systemPrompt,
                                        String userQuestion, String ragContext) {
        String historyContext = buildHistoryContext(sourceBizId, fromUserId, botUserId);
        if (StringUtils.isBlank(ragContext)) {
            return appendHistoryContext(systemPrompt, historyContext);
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
            return appendHistoryContext(systemPrompt, historyContext);
        }

        StringBuilder ref = new StringBuilder();
        for (Document doc : searchResult) {
            if (StringUtils.isBlank(doc.getText())) {
                continue;
            }
            ref.append("- ").append(doc.getText()).append('\n');
        }

        if (ref.length() == 0) {
            return appendHistoryContext(systemPrompt, historyContext);
        }

        String ragPrompt = systemPrompt + "\n\n请优先基于以下检索到的参考片段回答（RAG上下文）：\n" + ref;
        return appendHistoryContext(ragPrompt, historyContext);
    }

    private String appendHistoryContext(String systemPrompt, String historyContext) {
        if (StringUtils.isBlank(historyContext)) {
            return systemPrompt;
        }
        return systemPrompt + "\n\n以下是该用户与机器人此前的完整对话记录，请保持上下文连续性：\n" + historyContext;
    }

    private String buildHistoryContext(String sourceBizId, Long fromUserId, Long botUserId) {
        List<BotChatRecord> records = RedisClient.lRange(buildHistoryKey(fromUserId, botUserId), 0, MAX_HISTORY_ITEMS, BotChatRecord.class);
        if (records == null || records.isEmpty()) {
            return "";
        }

        String history = records.stream()
                .map(record -> "用户: " + record.getQuestion() + "\n机器人: " + record.getAnswer())
                .collect(Collectors.joining("\n\n"));
        log.debug("加载历史对话 sourceBizId={}, fromUserId={}, botUserId={}, size={}", sourceBizId, fromUserId, botUserId, records.size());
        return history;
    }

    private void saveConversation(String sourceBizId, Long fromUserId, Long botUserId, String question, String answer) {
        String key = buildHistoryKey(fromUserId, botUserId);
        RedisClient.rPush(key, new BotChatRecord().setQuestion(question).setAnswer(answer));
        RedisClient.lTrim(key, -MAX_HISTORY_ITEMS, -1);
        RedisClient.expire(key, HISTORY_EXPIRE_SECONDS);
        log.debug("保存历史对话 sourceBizId={}, fromUserId={}, botUserId={}", sourceBizId, fromUserId, botUserId);
    }

    private String buildHistoryKey(Long fromUserId, Long botUserId) {
        return "chat.bot.history." + fromUserId + "." + botUserId;
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

    @lombok.Data
    @lombok.experimental.Accessors(chain = true)
    private static class BotChatRecord {
        private String question;
        private String answer;
    }
}
