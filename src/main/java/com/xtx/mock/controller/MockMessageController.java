package com.xtx.mock.controller;

import cn.hutool.json.JSONObject;
import com.xtx.common.core.result.FrontResponse;
import com.xtx.mock.service.MessageMockService;
import com.xtx.mock.store.RuntimeMessageStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * MockMessageController — 站内消息模块控制器。
 * <p>
 * 职责：
 * <ul>
 *   <li>GET  /member/message     — 获取当前用户消息列表</li>
 *   <li>POST /member/message/read — 标记消息已读</li>
 * </ul>
 * <p>
 * 由 Spring 管理为 {@link RestController}，但运行时数据依赖
 * 由 {@link MockController} 在 {@code @PostConstruct} 中通过
 * {@link #setRuntimeTokens(Map)} 注入。
 */
@Slf4j
@RestController
public class MockMessageController {

    private final RuntimeMessageStore messageStore;
    private final MessageMockService messageService;

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /**
     * 构造器：创建 Store 和 Service 并加载持久化数据。
     * Spring 实例化时自动调用。
     */
    public MockMessageController() {
        this.messageStore = new RuntimeMessageStore();
        this.messageService = new MessageMockService(messageStore);
        this.messageStore.loadFromFile();
        log.info("MockMessageController initialized, store loaded from file");
    }

    /**
     * 由 MockController 在 @PostConstruct 中调用，注入运行时 token 引用。
     *
     * @param tokens MockController 的 runtimeTokens (Map<String, String>)
     */
    public void setRuntimeTokens(Map<String, String> tokens) {
        this.runtimeTokens = tokens;
        log.info("MockMessageController received runtime tokens reference");
    }

    // ==================== 消息列表 ====================

    /**
     * GET /member/message — 获取当前用户消息列表。
     * <p>
     * 保持响应结构：
     * <pre>
     * { "code": "20000", "msg": "成功", "result": { "summary": {...}, "items": [...] } }
     * </pre>
     */
    @GetMapping("/member/message")
    public FrontResponse<Object> memberMessages(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        JSONObject result = messageService.getMessages(uid);
        return FrontResponse.success(result);
    }

    // ==================== 标记已读 ====================

    /**
     * POST /member/message/read — 标记消息已读。
     * <p>
     * 支持：
     * <ul>
     *   <li>单条标记：{ "id": "msg_xxx" }</li>
     *   <li>全部标记：{ "readAll": true }</li>
     * </ul>
     */
    @PostMapping("/member/message/read")
    public FrontResponse<Object> markMessageRead(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> params) {
        String uid = requireUserId(authHeader);
        String id = params != null && params.get("id") != null ? params.get("id").toString() : "";
        boolean readAll = params != null && Boolean.TRUE.equals(params.get("readAll"));
        messageService.markRead(uid, id, readAll);
        return FrontResponse.success(Boolean.TRUE);
    }

    // ==================== 新用户初始化 ====================

    /**
     * 为新用户初始化默认消息。
     * 由 MockController.initNewUserFeatureData() 在注册时调用。
     *
     * @param userId 新注册用户 ID
     */
    public void initNewUserMessages(String userId) {
        messageService.initNewUserMessages(userId);
    }

    // ==================== Auth ====================

    /**
     * 要求请求必须携带有效 token，否则抛出 401。
     */
    private String requireUserId(String authHeader) {
        String userId = getUserIdFromToken(authHeader);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return userId;
    }

    /**
     * 返回 token 对应的 userId，未找到返回 null。
     */
    private String getUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) return null;
        return runtimeTokens.get(token);
    }
}
