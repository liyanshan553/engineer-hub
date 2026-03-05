package com.github.paicoding.forum.service.chatai.service.impl.springai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Spring AI (阿里云百炼) 配置类
 *
 * @author paicoding
 * @date 2026/3/5
 */
@Data
@Component
@ConfigurationProperties(prefix = "spring-ai")
public class SpringAiConfig {

    /**
     * 阿里云百炼模型名称，如 qwen-plus, qwen-turbo, qwen-max
     */
    private String model = "qwen-plus";

    /**
     * 是否开启 Function Calling
     */
    private boolean functionCallingEnabled = true;

    /**
     * Function Calling 的最大迭代次数（防止死循环）
     */
    private int maxToolCallRounds = 3;

    /**
     * System Prompt - 统一约束 AI 返回格式的提示词
     */
    private String systemPrompt = "你是技术派社区的AI助手「派聪明」，擅长回答技术问题和帮助用户使用社区功能。"
            + "请用中文回答，回答要简洁专业。"
            + "当你需要查询社区内容时，可以使用提供的工具进行查询。"
            + "回答时请注意格式统一：使用 Markdown 格式，代码用代码块包裹。";
}
