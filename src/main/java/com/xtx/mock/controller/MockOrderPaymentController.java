package com.xtx.mock.controller;

import cn.hutool.json.JSONObject;
import com.xtx.mock.service.order.OrderPaymentMockService;
import com.xtx.mock.service.order.StockChangeLogService;
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
 */
@Slf4j
@RestController
public class MockOrderPaymentController {

    private RuntimeOrderStore orderStore;
    private OrderPaymentMockService paymentService;
    private StockChangeLogService stockChangeLogService;

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /** MockController 的 masterSkus 引用（运行时 SKU 库存数据） */
    private JSONObject masterSkus;

    /** MockController 的 masterProducts 引用（运行时商品库存数据） */
    private JSONObject masterProducts;

    public MockOrderPaymentController() {
        this.orderStore = new RuntimeOrderStore();
        this.stockChangeLogService = new StockChangeLogService();
        log.info("MockOrderPaymentController initialized");
    }

    // ==================== Setter 注入 ====================

    public void setRuntimeTokens(Map<String, String> tokens) {
        this.runtimeTokens = tokens;
    }

    public void setRuntimeOrders(Map<String, JSONObject> orders) {
        this.orderStore.setRuntimeOrders(orders);
    }

    public void setSaveOrdersCallback(Runnable callback) {
        this.orderStore.setSaveOrdersCallback(callback);
    }

    public void setMasterSkus(JSONObject skus) {
        this.masterSkus = skus;
    }

    public void setMasterProducts(JSONObject products) {
        this.masterProducts = products;
    }

    public void setStockChangeLogService(StockChangeLogService service) {
        this.stockChangeLogService = service;
    }

    // ==================== Service 延迟初始化 ====================

    private OrderPaymentMockService getService() {
        if (paymentService == null) {
            this.paymentService = new OrderPaymentMockService(orderStore, stockChangeLogService);
            this.paymentService.setRuntimeSkus(masterSkus);
            this.paymentService.setRuntimeProducts(masterProducts);
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
     *   <li>校验订单状态：已支付直接返回成功（幂等），已取消拒绝支付</li>
     *   <li>确认扣减库存：lockedStock → soldStock</li>
     *   <li>更新订单状态 1→2，记录支付时间</li>
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

    @PostMapping("/member/order/{orderNo}/pay")
    public Object orderPayByOrderNo(
            @org.springframework.web.bind.annotation.PathVariable String orderNo,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> params) {
        String uid = requireUserId(authHeader);
        Object payChannel = params != null ? params.get("payChannel") : null;
        try {
            JSONObject result = getService().payByOrderNo(orderNo, uid, payChannel);
            if (result == null) {
                return FrontResponse.failure("订单不存在");
            }
            return FrontResponse.success(result);
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
