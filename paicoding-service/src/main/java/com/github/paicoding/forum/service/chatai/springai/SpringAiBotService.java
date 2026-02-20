package com.github.paicoding.forum.service.chatai.springai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 基于 Spring AI 的评论机器人统一调用入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpringAiBotService {
    private final ChatClient.Builder chatClientBuilder;

    public void ask(String systemPrompt, String userQuestion, Consumer<String> consumer) {
        ChatClient.CallResponseSpec spec = chatClientBuilder.build()
                .prompt()
                .system(systemPrompt)
                .user(userQuestion)
                .call();
        String content = spec.content();
        if (StringUtils.isNotBlank(content)) {
            consumer.accept(content.trim());
        } else {
            log.warn("spring-ai 评论机器人返回空内容");
        }
    }
}
