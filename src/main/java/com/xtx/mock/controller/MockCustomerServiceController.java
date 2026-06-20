package com.xtx.mock.controller;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.common.core.result.FrontResponse;
import com.xtx.mock.service.CustomerServiceMockService;
import com.xtx.mock.store.RuntimeCustomerServiceStore;
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
 * MockCustomerServiceController — 客服模块控制器。
 * <p>
 * 职责：
 * <ul>
 *   <li>POST /member/customer-service/chat — 客服问答</li>
 *   <li>GET  /member/customer-service/history — 获取聊天记录</li>
 *   <li>POST /member/customer-service/ticket — 提交客服工单</li>
 *   <li>GET  /member/customer-service/ticket — 获取工单列表</li>
 * </ul>
 * <p>
 * 由 Spring 管理为 {@link RestController}，但运行时数据依赖
 * 由 {@link MockController} 在 {@code @PostConstruct} 中通过
 * {@link #setRuntimeData(Map, Map)} 注入。
 */
@Slf4j
@RestController
public class MockCustomerServiceController {

    private final RuntimeCustomerServiceStore csStore;
    private final CustomerServiceMockService csService;

    /** MockController 的 runtimeOrders 引用（@PostConstruct 注入） */
    private Map<String, JSONObject> runtimeOrders;

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /**
     * 构造器：创建 Store 和 Service 并加载持久化数据。
     * Spring 实例化时自动调用。
     */
    public MockCustomerServiceController() {
        this.csStore = new RuntimeCustomerServiceStore();
        this.csService = new CustomerServiceMockService(csStore);
        this.csStore.loadFromFiles();
        log.info("MockCustomerServiceController initialized, store loaded from files");
    }

    /**
     * 由 MockController 在 @PostConstruct 中调用，注入运行时数据引用。
     *
     * @param orders  MockController 的 runtimeOrders (Map<String, JSONObject>)
     * @param tokens  MockController 的 runtimeTokens (Map<String, String>)
     */
    public void setRuntimeData(Map<String, JSONObject> orders, Map<String, String> tokens) {
        this.runtimeOrders = orders;
        this.runtimeTokens = tokens;
        log.info("MockCustomerServiceController received runtime data references");
    }

    // ==================== Chat ====================

    /**
     * POST /member/customer-service/chat — 客服问答
     */
    @PostMapping("/member/customer-service/chat")
    public FrontResponse<Object> customerServiceChat(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> params) {
        String uid = requireUserId(authHeader);
        String message = params.get("message") != null ? params.get("message").toString() : "";

        JSONObject result = csService.chat(message, uid, runtimeOrders);
        return FrontResponse.success(result);
    }

    /**
     * GET /member/customer-service/history — 获取聊天记录
     */
    @GetMapping("/member/customer-service/history")
    public FrontResponse<Object> customerServiceHistory(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        JSONArray chats = csService.getHistory(uid);
        JSONObject result = new JSONObject();
        result.set("items", chats);
        return FrontResponse.success(result);
    }

    // ==================== Ticket ====================

    /**
     * POST /member/customer-service/ticket — 提交客服工单
     */
    @PostMapping("/member/customer-service/ticket")
    public FrontResponse<Object> customerServiceCreateTicket(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> params) {
        String uid = requireUserId(authHeader);
        String type = params.get("type") != null ? params.get("type").toString() : "其他";
        String content = params.get("content") != null ? params.get("content").toString() : "";
        String orderId = params.get("orderId") != null ? params.get("orderId").toString() : "";

        JSONObject ticket = csService.createTicket(uid, type, content, orderId);
        return FrontResponse.success(ticket);
    }

    /**
     * GET /member/customer-service/ticket — 获取工单列表
     */
    @GetMapping("/member/customer-service/ticket")
    public FrontResponse<Object> customerServiceTicketList(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        JSONArray tickets = csService.getTickets(uid);
        JSONObject result = new JSONObject();
        result.set("items", tickets);
        return FrontResponse.success(result);
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
