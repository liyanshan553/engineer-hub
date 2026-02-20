package com.github.paicoding.forum.service.chatai.springai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 评论机器人 Spring AI 配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.bot.spring-ai")
public class AiBotSpringAiProperties {
    /**
     * 评论机器人是否启用 spring-ai 通道。
     */
    private boolean enabled = false;

    /**
     * 机器人默认模型。
     */
    private String model;
}
