package com.github.paicoding.forum.service.chatai.service.impl.springai;

import cn.idev.excel.util.StringUtils;
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.tools.ToolFunction;
import com.github.paicoding.forum.api.model.enums.ChatAnswerTypeEnum;
import com.github.paicoding.forum.api.model.enums.ai.AiChatStatEnum;
import com.github.paicoding.forum.api.model.vo.chat.ChatItemVo;
import com.github.paicoding.forum.api.model.vo.chat.ChatRecordsVo;
import com.github.paicoding.forum.service.chatai.constants.ChatConstants;
import com.github.paicoding.forum.service.chatai.service.impl.springai.tool.FunctionToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Spring AI 集成层 - 对接阿里云百炼大模型
 * 核心特性：
 * 1. 支持 Function Calling（工具调用），大模型可自动调用注册的 Function Tool
 * 2. 统一响应结构化解析，解决不同模型返回格式不一致问题
 * 3. 支持多轮工具调用迭代（大模型可连续调用多个工具）
 * 4. 支持同步和流式两种交互模式
 *
 * @author paicoding
 * @date 2026/3/5
 */
@Slf4j
@Component
public class SpringAiIntegration {

    @Autowired
    private SpringAiConfig config;

    @Autowired
    private FunctionToolRegistry toolRegistry;

    /**
     * 同步调用（支持 Function Calling）
     * 完整的 Function Calling 流程：
     * 1. 发送用户消息和工具定义给大模型
     * 2. 如果大模型返回 tool_calls，执行工具并将结果回传
     * 3. 重复直到大模型给出最终文本回答
     */
    public boolean directReturn(Long user, ChatItemVo chat) {
        try {
            Generation gen = new Generation();
            List<Message> messages = new ArrayList<>();

            // 添加 System Prompt，统一约束 AI 回答格式
            messages.add(Message.builder()
                    .role(Role.SYSTEM.getValue())
                    .content(config.getSystemPrompt())
                    .build());

            // 添加用户消息
            messages.add(Message.builder()
                    .role(Role.USER.getValue())
                    .content(chat.getQuestion())
                    .build());

            // 构建请求参数（带 Function Tool 定义）
            GenerationParam param = buildParam(messages);

            // Function Calling 迭代循环
            int round = 0;
            while (round < config.getMaxToolCallRounds()) {
                GenerationResult result = gen.call(param);
                SpringAiStructuredResponse parsed = SpringAiResponseParser.parse(result);

                if (!parsed.isToolCalled()) {
                    // 没有工具调用，直接返回文本结果
                    chat.initAnswer(parsed.getContent());
                    log.info("Spring AI 直接返回, 第{}轮, 结果: {}", round + 1, parsed.getContent());
                    return true;
                }

                // 有工具调用：执行工具并构造回传消息
                log.info("Spring AI 触发 Function Calling, 第{}轮, 工具: {}", round + 1, parsed.getToolCalls());
                Message assistantMsg = result.getOutput().getChoices().get(0).getMessage();
                messages.add(assistantMsg);

                for (SpringAiStructuredResponse.ToolCallInfo toolCall : parsed.getToolCalls()) {
                    String toolResult = toolRegistry.executeTool(toolCall.getToolName(), toolCall.getArguments());
                    toolCall.setResult(toolResult);

                    // 将工具结果作为 tool 角色消息回传给大模型
                    messages.add(Message.builder()
                            .role("tool")
                            .content(toolResult)
                            .name(toolCall.getToolName())
                            .build());
                }

                // 更新参数继续对话
                param = buildParam(messages);
                round++;
            }

            // 超过最大迭代次数
            chat.initAnswer("AI 处理超时，请尝试简化问题后重试。");
            return true;

        } catch (Exception e) {
            log.error("Spring AI 调用失败: {}", e.getMessage(), e);
            chat.initAnswer("AI 服务暂时不可用: " + e.getMessage());
            return false;
        }
    }

    /**
     * 流式返回（支持 Function Calling）
     * 流式模式下如果触发了 Function Calling，先同步完成工具调用，
     * 然后再用流式方式返回最终的文本回答
     */
    public void streamReturn(Long user, ChatRecordsVo chatRecord, BiConsumer<AiChatStatEnum, ChatRecordsVo> callback) {
        try {
            ChatItemVo item = chatRecord.getRecords().get(0);
            Generation gen = new Generation();

            // 构建消息列表（支持多轮对话上下文）
            List<Message> messages = new ArrayList<>();
            messages.add(Message.builder()
                    .role(Role.SYSTEM.getValue())
                    .content(config.getSystemPrompt())
                    .build());

            // 加入历史对话上下文
            List<Message> historyMsgs = ChatConstants.toMsgList(chatRecord.getRecords(), this::toMsg);
            messages.addAll(historyMsgs);

            // 先用非流式模式检查是否需要 Function Calling
            if (config.isFunctionCallingEnabled() && toolRegistry.hasTools()) {
                messages = handleFunctionCallingRounds(gen, messages);
            }

            // 流式返回最终结果
            GenerationParam streamParam = GenerationParam.builder()
                    .model(config.getModel())
                    .messages(messages)
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .incrementalOutput(true)
                    .build();

            Semaphore semaphore = new Semaphore(0);
            StringBuilder fullContent = new StringBuilder();

            gen.streamCall(streamParam, new ResultCallback<GenerationResult>() {
                @Override
                public void onEvent(GenerationResult message) {
                    String content = SpringAiResponseParser.parseStreamDelta(message);
                    if (!content.isEmpty()) {
                        fullContent.append(content);
                        item.appendAnswer(content);
                        callback.accept(AiChatStatEnum.MID, chatRecord);
                    }
                }

                @Override
                public void onError(Exception err) {
                    log.error("Spring AI 流式返回异常: {}", err.getMessage());
                    callback.accept(AiChatStatEnum.ERROR, chatRecord);
                    semaphore.release();
                }

                @Override
                public void onComplete() {
                    item.setAnswerType(ChatAnswerTypeEnum.STREAM_END);
                    callback.accept(AiChatStatEnum.END, chatRecord);
                    log.info("Spring AI 流式返回完成");
                    semaphore.release();
                }
            });

            semaphore.acquire();
            log.info("Spring AI 完整返回: \n{}", fullContent.toString());

        } catch (Exception e) {
            log.error("Spring AI 流式调用失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理 Function Calling 迭代
     * 不断向大模型发送消息，直到它不再请求工具调用
     *
     * @return 更新后的消息列表（包含所有工具调用和结果）
     */
    private List<Message> handleFunctionCallingRounds(Generation gen, List<Message> messages) throws Exception {
        int round = 0;
        while (round < config.getMaxToolCallRounds()) {
            GenerationParam param = buildParam(messages);
            GenerationResult result = gen.call(param);

            if (!SpringAiResponseParser.hasToolCalls(result)) {
                // 无工具调用，返回当前消息列表
                break;
            }

            // 提取 assistant 的 tool_calls 消息
            Message assistantMsg = result.getOutput().getChoices().get(0).getMessage();
            messages.add(assistantMsg);

            // 执行每个工具调用
            for (ToolCallBase toolCall : assistantMsg.getToolCalls()) {
                if (toolCall instanceof ToolCallFunction) {
                    ToolCallFunction func = (ToolCallFunction) toolCall;
                    String toolName = func.getFunction().getName();
                    String arguments = func.getFunction().getArguments();

                    log.info("Function Calling 第{}轮: 调用工具 {}({})", round + 1, toolName, arguments);
                    String toolResult = toolRegistry.executeTool(toolName, arguments);

                    messages.add(Message.builder()
                            .role("tool")
                            .content(toolResult)
                            .name(toolName)
                            .build());
                }
            }
            round++;
        }
        return messages;
    }

    /**
     * 构建带 Function Tool 定义的请求参数
     */
    private GenerationParam buildParam(List<Message> messages) {
        GenerationParam.GenerationParamBuilder<?, ?> builder = GenerationParam.builder()
                .model(config.getModel())
                .messages(messages)
                .resultFormat(GenerationParam.ResultFormat.MESSAGE);

        // 如果开启了 Function Calling 且有注册的工具，则添加工具定义
        if (config.isFunctionCallingEnabled() && toolRegistry.hasTools()) {
            List<ToolFunction> tools = toolRegistry.buildToolFunctions();
            builder.tools(new ArrayList<>(tools));
        }

        return builder.build();
    }

    /**
     * 将 ChatItemVo 转换为 DashScope Message 格式
     */
    private List<Message> toMsg(ChatItemVo item) {
        List<Message> list = new ArrayList<>(2);
        if (item.getQuestion().startsWith(ChatConstants.PROMPT_TAG)) {
            list.add(Message.builder()
                    .role(Role.SYSTEM.getValue())
                    .content(item.getQuestion().substring(ChatConstants.PROMPT_TAG.length()))
                    .build());
            return list;
        }

        list.add(Message.builder()
                .role(Role.USER.getValue())
                .content(item.getQuestion())
                .build());
        if (StringUtils.isNotBlank(item.getAnswer())) {
            list.add(Message.builder()
                    .role(Role.ASSISTANT.getValue())
                    .content(item.getAnswer())
                    .build());
        }
        return list;
    }
}
