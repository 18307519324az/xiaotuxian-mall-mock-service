package com.xtx.mock.controller;

import com.xtx.mock.service.order.OrderActionMockService;
import com.xtx.mock.store.RuntimeOrderStore;
import com.xtx.common.core.result.FrontResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * MockOrderActionController — 订单操作（状态变更）模块控制器。
 * <p>
 * 职责：
 * <ul>
 *   <li>PUT    /member/order/{orderId}/cancel   — 取消订单</li>
 *   <li>DELETE /member/order                    — 删除订单</li>
 *   <li>PUT    /member/order/{orderId}/ship     — 模拟发货</li>
 *   <li>PUT    /member/order/{orderId}/receipt  — 确认收货</li>
 * </ul>
 * <p>
 * 由 Spring 管理为 {@link RestController}，但运行时数据依赖
 * 由 {@link com.xtx.mock.controller.MockController} 在 {@code @PostConstruct} 中通过 setter 注入：
 * <ul>
 *   <li>{@link #setRuntimeTokens(Map)}        — Token 映射（用于认证）</li>
 *   <li>{@link #setRuntimeOrders(Map)}        — 运行时订单数据引用</li>
 *   <li>{@link #setSaveOrdersCallback(Runnable)} — 订单持久化回调</li>
 * </ul>
 */
@Slf4j
@RestController
public class MockOrderActionController {

    private RuntimeOrderStore orderStore;
    private OrderActionMockService actionService;

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /**
     * 构造器：创建 Store 并占位 Service。
     */
    public MockOrderActionController() {
        this.orderStore = new RuntimeOrderStore();
        log.info("MockOrderActionController initialized");
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

    private OrderActionMockService getService() {
        if (actionService == null) {
            this.actionService = new OrderActionMockService(orderStore);
        }
        return actionService;
    }

    // ==================== PUT /member/order/{orderId}/cancel ====================

    /**
     * PUT /member/order/{orderId}/cancel — 取消订单。
     * 规则：仅 orderState === 1（待付款）可取消 → 状态变为 6（已取消）。
     */
    @PutMapping("/member/order/{orderId}/cancel")
    public Object orderCancel(
            @PathVariable String orderId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> params) {
        String uid = requireUserId(authHeader);
        String cancelReason = params != null ? (String) params.get("cancelReason") : null;
        try {
            cn.hutool.json.JSONObject order = getService().cancel(orderId, uid, cancelReason);
            if (order == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在: " + orderId);
            }
            return FrontResponse.success(Boolean.TRUE);
        } catch (IllegalStateException e) {
            return FrontResponse.failure(e.getMessage());
        }
    }

    // ==================== DELETE /member/order ====================

    /**
     * DELETE /member/order — 删除订单。
     */
    @DeleteMapping("/member/order")
    public Object orderDelete(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> params) {
        String uid = requireUserId(authHeader);
        @SuppressWarnings("unchecked")
        List<String> ids = params != null ? (List<String>) params.get("ids") : null;
        getService().delete(uid, ids);
        return FrontResponse.success(Boolean.TRUE);
    }

    // ==================== PUT /member/order/{orderId}/ship ====================

    /**
     * PUT /member/order/{orderId}/ship — 模拟发货。
     * 规则：仅 orderState === 2（待发货）可发货 → 状态变为 3（待收货）。
     */
    @PutMapping("/member/order/{orderId}/ship")
    public Object orderShip(
            @PathVariable String orderId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        try {
            cn.hutool.json.JSONObject order = getService().ship(orderId, uid);
            if (order == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在: " + orderId);
            }
            return FrontResponse.success(order);
        } catch (IllegalStateException e) {
            return FrontResponse.failure(e.getMessage());
        }
    }

    // ==================== PUT /member/order/{orderId}/receipt ====================

    /**
     * PUT /member/order/{orderId}/receipt — 确认收货。
     * 规则：仅 orderState === 3（待收货）可确认 → 状态变为 4（待评价）。
     */
    @PutMapping("/member/order/{orderId}/receipt")
    public Object orderReceipt(
            @PathVariable String orderId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        try {
            cn.hutool.json.JSONObject order = getService().receipt(orderId, uid);
            if (order == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在: " + orderId);
            }
            return FrontResponse.success(Boolean.TRUE);
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
