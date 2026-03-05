package com.github.paicoding.forum.service.chatai.service.impl.springai;

import com.alibaba.dashscope.aigc.generation.GenerationOutput;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI 响应解析器
 * 统一处理大模型不同类型的返回结果（纯文本、Function Calling、流式等）
 * 将所有结果转换为统一的 SpringAiStructuredResponse 格式
 *
 * @author paicoding
 * @date 2026/3/5
 */
@Slf4j
public class SpringAiResponseParser {

    /**
     * 解析大模型返回结果为统一格式
     *
     * @param result DashScope 返回的原始结果
     * @return 统一结构化响应
     */
    public static SpringAiStructuredResponse parse(GenerationResult result) {
        SpringAiStructuredResponse response = new SpringAiStructuredResponse();

        if (result == null || result.getOutput() == null) {
            response.setContent("AI 未返回有效结果");
            response.setResponseType("text");
            response.setToolCalled(false);
            return response;
        }

        GenerationOutput output = result.getOutput();
        List<GenerationOutput.Choice> choices = output.getChoices();

        if (choices == null || choices.isEmpty()) {
            response.setContent(output.getText() != null ? output.getText() : "AI 未返回有效结果");
            response.setResponseType("text");
            response.setToolCalled(false);
            return response;
        }

        GenerationOutput.Choice choice = choices.get(0);
        Message message = choice.getMessage();

        // 检查是否包含 tool_calls（Function Calling）
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            response.setToolCalled(true);
            response.setResponseType("tool");
            List<SpringAiStructuredResponse.ToolCallInfo> toolCalls = new ArrayList<>();

            for (ToolCallBase toolCall : message.getToolCalls()) {
                if (toolCall instanceof ToolCallFunction) {
                    ToolCallFunction functionCall = (ToolCallFunction) toolCall;
                    SpringAiStructuredResponse.ToolCallInfo info = new SpringAiStructuredResponse.ToolCallInfo();
                    info.setToolName(functionCall.getFunction().getName());
                    info.setArguments(functionCall.getFunction().getArguments());
                    toolCalls.add(info);
                }
            }
            response.setToolCalls(toolCalls);
            // tool_calls 模式下，content 可能为空，后续由工具结果填充
            response.setContent(message.getContent() != null ? message.getContent() : "");
        } else {
            // 纯文本回答
            response.setToolCalled(false);
            response.setResponseType("text");
            response.setContent(message.getContent() != null ? message.getContent() : "");
        }

        return response;
    }

    /**
     * 从流式返回的增量结果中提取文本内容
     *
     * @param result 增量返回的结果
     * @return 增量文本
     */
    public static String parseStreamDelta(GenerationResult result) {
        if (result == null || result.getOutput() == null) {
            return "";
        }
        List<GenerationOutput.Choice> choices = result.getOutput().getChoices();
        if (choices == null || choices.isEmpty()) {
            return "";
        }
        Message message = choices.get(0).getMessage();
        return message != null && message.getContent() != null ? message.getContent() : "";
    }

    /**
     * 判断返回结果是否包含 Function Calling 请求
     */
    public static boolean hasToolCalls(GenerationResult result) {
        if (result == null || result.getOutput() == null) {
            return false;
        }
        List<GenerationOutput.Choice> choices = result.getOutput().getChoices();
        if (choices == null || choices.isEmpty()) {
            return false;
        }
        Message message = choices.get(0).getMessage();
        return message.getToolCalls() != null && !message.getToolCalls().isEmpty();
    }
}
