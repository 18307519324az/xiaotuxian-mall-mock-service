package com.xtx.mock.controller;

import cn.hutool.json.JSONObject;
import com.xtx.mock.service.order.OrderPaymentMockService;
import com.xtx.mock.store.RuntimeOrderStore;
import com.xtx.common.core.result.FrontResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * MockOrderPaymentController — 订单支付模块控制器。
 * <p>
 * 职责：
 * <ul>
 *   <li>POST /member/pay — 支付订单</li>
 * </ul>
 * <p>
 * 由 Spring 管理为 {@link RestController}，但运行时数据依赖
 * 由 {@link com.xtx.mock.controller.MockController} 在 {@code @PostConstruct} 中通过 setter 注入：
 * <ul>
 *   <li>{@link #setRuntimeTokens(Map)}        — Token 映射（用于认证）</li>
 *   <li>{@link #setRuntimeOrders(Map)}        — 运行时订单数据引用</li>
 *   <li>{@link #setSaveOrdersCallback(Runnable)} — 订单持久化回调</li>
 * </ul>
 * <p>
 * 不创建订单，不处理购物车清理，不重新计算订单金额。
 */
@Slf4j
@RestController
public class MockOrderPaymentController {

    private RuntimeOrderStore orderStore;
    private OrderPaymentMockService paymentService;

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /**
     * 构造器：创建 Store 并占位 Service。
     */
    public MockOrderPaymentController() {
        this.orderStore = new RuntimeOrderStore();
        log.info("MockOrderPaymentController initialized");
    }

    // ==================== Setter 注入 ====================

    public void setRuntimeTokens(Map<String, String> tokens) {
        this.runtimeTokens = tokens;
    }

    public void setRuntimeOrders(Map<String, cn.hutool.json.JSONObject> orders) {
        this.orderStore.setRuntimeOrders(orders);
    }

    public void setSaveOrdersCallback(Runnable callback) {
        this.orderStore.setSaveOrdersCallback(callback);
    }

    // ==================== Service 延迟初始化 ====================

    private OrderPaymentMockService getService() {
        if (paymentService == null) {
            this.paymentService = new OrderPaymentMockService(orderStore);
        }
        return paymentService;
    }

    // ==================== POST /member/pay ====================

    /**
     * POST /member/pay — 支付订单。
     * <p>
     * 规则：
     * <ol>
     *   <li>校验 orderId 必填且存在</li>
     *   <li>校验 orderState 必须是 1（待付款）</li>
     *   <li>更新 orderState → 2（待发货），记录支付时间</li>
     *   <li>保存支付渠道</li>
     * </ol>
     */
    @PostMapping("/member/pay")
    public Object orderPay(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> params) {
        String uid = requireUserId(authHeader);
        String orderId = (String) params.get("orderId");
        if (orderId == null || orderId.isBlank()) {
            return FrontResponse.failure("订单ID不能为空");
        }

        Object payChannel = params.get("payChannel");
        try {
            JSONObject result = getService().pay(orderId, uid, payChannel);
            if (result == null) {
                return FrontResponse.failure("订单不存在");
            }
            return FrontResponse.success(result);
        } catch (IllegalStateException e) {
            return FrontResponse.failure(e.getMessage());
        }
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
     * 从 Authorization header 提取 userId。
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
