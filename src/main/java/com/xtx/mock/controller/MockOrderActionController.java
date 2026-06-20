package com.xtx.mock.controller;

import com.xtx.mock.service.order.OrderActionMockService;
import com.xtx.mock.service.order.StockChangeLogService;
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
 */
@Slf4j
@RestController
public class MockOrderActionController {

    private RuntimeOrderStore orderStore;
    private OrderActionMockService actionService;
    private StockChangeLogService stockChangeLogService;

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /** MockController 的 masterSkus 引用（运行时 SKU 库存数据） */
    private cn.hutool.json.JSONObject masterSkus;

    /** MockController 的 masterProducts 引用（运行时商品库存数据） */
    private cn.hutool.json.JSONObject masterProducts;

    public MockOrderActionController() {
        this.orderStore = new RuntimeOrderStore();
        this.stockChangeLogService = new StockChangeLogService();
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

    public void setMasterSkus(cn.hutool.json.JSONObject skus) {
        this.masterSkus = skus;
    }

    public void setMasterProducts(cn.hutool.json.JSONObject products) {
        this.masterProducts = products;
    }

    public void setStockChangeLogService(StockChangeLogService service) {
        this.stockChangeLogService = service;
    }

    // ==================== Service 延迟初始化 ====================

    private OrderActionMockService getService() {
        if (actionService == null) {
            this.actionService = new OrderActionMockService(orderStore, stockChangeLogService);
            this.actionService.setRuntimeSkus(masterSkus);
            this.actionService.setRuntimeProducts(masterProducts);
        }
        return actionService;
    }

    // ==================== PUT /member/order/{orderId}/cancel ====================

    /**
     * PUT /member/order/{orderId}/cancel — 取消订单。
     * <p>
     * 规则：
     * <ol>
     *   <li>仅待付款(1)订单可取消 → 状态变为 6（已取消）</li>
     *   <li>已取消订单重复调用直接返回成功（幂等）</li>
     *   <li>已支付订单不能取消</li>
     *   <li>释放锁定库存：lockedStock → availableStock</li>
     * </ol>
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

    private String requireUserId(String authHeader) {
        String userId = getUserIdFromToken(authHeader);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return userId;
    }

    private String getUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) return null;
        return runtimeTokens.get(token);
    }
}
