package com.github.paicoding.forum.service.chatai.service.impl.springai.tool;

import com.github.paicoding.forum.api.model.vo.user.dto.BaseUserInfoDTO;
import com.github.paicoding.forum.service.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户信息查询工具 - 让 AI 能够查询技术派社区的用户信息
 * 当用户问到某个社区用户的信息时，大模型会自动调用此工具查询
 *
 * @author paicoding
 * @date 2026/3/5
 */
@Slf4j
@Component
@FunctionTool(
        name = "user_query",
        description = "查询技术派社区用户的基本信息。当用户询问某个社区成员的信息、积分、角色等时，调用此工具。参数 userId 为用户ID。"
)
public class UserQueryTool implements IFunctionToolService {

    @Autowired
    private UserService userService;

    @Override
    public String execute(Map<String, Object> params) {
        Object userIdObj = params.get("userId");
        if (userIdObj == null) {
            return "请提供用户ID进行查询。";
        }

        Long userId;
        try {
            userId = Long.parseLong(String.valueOf(userIdObj));
        } catch (NumberFormatException e) {
            return "用户ID格式不正确，请提供数字格式的用户ID。";
        }

        log.info("UserQueryTool 被调用, userId={}", userId);

        try {
            BaseUserInfoDTO userInfo = userService.queryBasicUserInfo(userId);
            if (userInfo == null) {
                return "未找到ID为 " + userId + " 的用户。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("用户信息:\n");
            sb.append("- 用户名: ").append(userInfo.getUserName()).append("\n");
            sb.append("- 用户ID: ").append(userInfo.getUserId()).append("\n");
            if (userInfo.getProfile() != null) {
                sb.append("- 个人简介: ").append(userInfo.getProfile()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("查询用户信息失败: {}", e.getMessage(), e);
            return "用户信息查询暂时不可用，请稍后再试。";
        }
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> userId = new LinkedHashMap<>();
        userId.put("type", "integer");
        userId.put("description", "要查询的用户ID");
        properties.put("userId", userId);

        schema.put("properties", properties);
        schema.put("required", new String[]{"userId"});
        return schema;
    }
}
