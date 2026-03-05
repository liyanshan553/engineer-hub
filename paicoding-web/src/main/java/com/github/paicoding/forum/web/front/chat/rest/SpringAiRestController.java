package com.github.paicoding.forum.web.front.chat.rest;

import com.github.paicoding.forum.api.model.context.ReqInfoContext;
import com.github.paicoding.forum.api.model.enums.ai.AISourceEnum;
import com.github.paicoding.forum.api.model.vo.ResVo;
import com.github.paicoding.forum.api.model.vo.chat.ChatRecordsVo;
import com.github.paicoding.forum.core.permission.Permission;
import com.github.paicoding.forum.core.permission.UserRole;
import com.github.paicoding.forum.service.chatai.ChatFacade;
import com.github.paicoding.forum.service.chatai.service.impl.springai.tool.FunctionToolRegistry;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Spring AI 聊天控制器
 * 提供基于阿里云百炼大模型的 AI 对话接口，支持 Function Calling
 *
 * @author paicoding
 * @date 2026/3/5
 */
@Slf4j
@RestController
@RequestMapping("/chat/api/springai")
public class SpringAiRestController {

    @Autowired
    private ChatFacade chatFacade;

    @Autowired
    private FunctionToolRegistry functionToolRegistry;

    /**
     * 同步聊天接口 - 支持 Function Calling
     * 大模型会自动判断是否需要调用工具，并在工具执行后返回最终回答
     */
    @Permission(role = UserRole.LOGIN)
    @PostMapping("/chat")
    public ResVo<ChatRecordsVo> chat(@RequestBody SpringAiChatReq req) {
        log.info("Spring AI 聊天请求: question={}", req.getQuestion());
        ChatRecordsVo result = chatFacade.chat(AISourceEnum.SPRING_AI, req.getQuestion());
        return ResVo.ok(result);
    }

    /**
     * 异步聊天接口 - 通过 WebSocket 流式返回
     * 与现有的 WebSocket 聊天机制一致，用户在前端选择 SPRING_AI 模型即可
     */
    @Permission(role = UserRole.LOGIN)
    @PostMapping("/asyncChat")
    public ResVo<ChatRecordsVo> asyncChat(@RequestBody SpringAiChatReq req) {
        log.info("Spring AI 异步聊天请求: question={}", req.getQuestion());
        ChatRecordsVo result = chatFacade.autoChat(AISourceEnum.SPRING_AI, req.getQuestion(), vo -> {
            log.info("Spring AI 异步回调: {}", vo);
        });
        return ResVo.ok(result);
    }

    /**
     * 查询已注册的 Function Tool 列表
     * 返回当前系统中所有可用的 AI 工具列表
     */
    @GetMapping("/tools")
    public ResVo<List<String>> listTools() {
        return ResVo.ok(functionToolRegistry.getToolNames());
    }

    /**
     * 聊天请求体
     */
    @Data
    public static class SpringAiChatReq {
        /**
         * 用户提问内容
         */
        private String question;
    }
}
