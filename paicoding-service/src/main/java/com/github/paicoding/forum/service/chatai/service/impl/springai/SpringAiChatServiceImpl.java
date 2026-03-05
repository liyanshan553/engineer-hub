package com.github.paicoding.forum.service.chatai.service.impl.springai;

import com.github.paicoding.forum.api.model.enums.ai.AISourceEnum;
import com.github.paicoding.forum.api.model.enums.ai.AiChatStatEnum;
import com.github.paicoding.forum.api.model.vo.chat.ChatItemVo;
import com.github.paicoding.forum.api.model.vo.chat.ChatRecordsVo;
import com.github.paicoding.forum.service.chatai.service.AbsChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.BiConsumer;

/**
 * Spring AI 聊天服务实现
 * 接入阿里云百炼大模型，支持 Function Calling 和结构化返回
 *
 * @author paicoding
 * @date 2026/3/5
 */
@Slf4j
@Service
public class SpringAiChatServiceImpl extends AbsChatService {

    @Autowired
    private SpringAiIntegration springAiIntegration;

    @Override
    public AiChatStatEnum doAnswer(Long user, ChatItemVo chat) {
        if (springAiIntegration.directReturn(user, chat)) {
            return AiChatStatEnum.END;
        }
        return AiChatStatEnum.ERROR;
    }

    @Override
    public AiChatStatEnum doAsyncAnswer(Long user, ChatRecordsVo chatRes, BiConsumer<AiChatStatEnum, ChatRecordsVo> consumer) {
        springAiIntegration.streamReturn(user, chatRes, consumer);
        return AiChatStatEnum.IGNORE;
    }

    @Override
    public AISourceEnum source() {
        return AISourceEnum.SPRING_AI;
    }

    @Override
    public boolean asyncFirst() {
        return true;
    }
}
