package com.github.paicoding.forum.service.chatai.service.impl.springai;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Spring AI 统一结构化响应 VO
 * 解决不同 AI 大模型返回结果结构不一致的问题
 * 通过 Prompt 约束 + 结构化解析，统一返回格式
 *
 * @author paicoding
 * @date 2026/3/5
 */
@Data
public class SpringAiStructuredResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * AI 回答的纯文本内容
     */
    private String content;

    /**
     * 是否调用了 Function Tool
     */
    private boolean toolCalled;

    /**
     * 调用的工具列表（如果有）
     */
    private List<ToolCallInfo> toolCalls;

    /**
     * 回答类型: text-纯文本回答, tool-工具调用后的回答, structured-结构化数据回答
     */
    private String responseType;

    /**
     * 工具调用信息
     */
    @Data
    public static class ToolCallInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * 工具名称
         */
        private String toolName;

        /**
         * 工具入参
         */
        private String arguments;

        /**
         * 工具返回结果
         */
        private String result;
    }
}
