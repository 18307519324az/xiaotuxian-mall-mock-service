package com.xtx.mock.controller;

import cn.hutool.json.JSONObject;
import com.xtx.mock.service.aftersale.AfterSaleMockService;
import com.xtx.mock.store.RuntimeAfterSaleStore;
import com.xtx.common.core.result.FrontResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MockAfterSaleController — 售后模块控制器。
 * <p>
 * 职责：
 * <ul>
 *   <li>GET  /member/after-sale      — 获取当前用户售后列表及可售后商品候选项</li>
 *   <li>POST /member/after-sale      — 提交售后申请</li>
 * </ul>
 * <p>
 * 由 Spring 管理为 {@link RestController}，但运行时数据依赖
 * 由 {@link MockController} 在 {@code @PostConstruct} 中通过 setter 注入：
 * <ul>
 *   <li>{@link #setRuntimeTokens(Map)} — Token 映射（用于认证）</li>
 *   <li>{@link #setRuntimeOrders(Map)} — 订单数据（只读，用于构建候选商品和校验）</li>
 *   <li>{@link #setFeatureCounter(AtomicInteger)} — ID 生成器</li>
 * </ul>
 */
@Slf4j
@RestController
public class MockAfterSaleController {

    private final RuntimeAfterSaleStore afterSaleStore;
    private final AfterSaleMockService afterSaleService;

    // ==================== 注入的依赖引用 ====================

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /** MockController 的 runtimeOrders 引用（只读，用于候选商品构建和订单校验） */
    private Map<String, JSONObject> runtimeOrders;

    /** MockController 的 featureCounter 引用（ID 生成） */
    private AtomicInteger featureCounter;

    /**
     * 构造器：创建 Store 和 Service 并加载持久化数据。
     */
    public MockAfterSaleController() {
        this.afterSaleStore = new RuntimeAfterSaleStore();
        this.afterSaleService = new AfterSaleMockService(afterSaleStore);
        this.afterSaleStore.loadFromFile();
        log.info("MockAfterSaleController initialized, store loaded from file");
    }

    // ==================== Setter 注入 ====================

    public void setRuntimeTokens(Map<String, String> tokens) {
        this.runtimeTokens = tokens;
    }

    public void setRuntimeOrders(Map<String, JSONObject> orders) {
        this.runtimeOrders = orders;
    }

    public void setFeatureCounter(AtomicInteger counter) {
        this.featureCounter = counter;
    }

    /**
     * 返回底层 Store 引用，供其他模块在重置/初始化时操作售后数据。
     */
    public RuntimeAfterSaleStore getStore() {
        return afterSaleStore;
    }

    /**
     * 返回底层 Service 引用，供其他模块调用售后业务逻辑。
     */
    public AfterSaleMockService getService() {
        return afterSaleService;
    }

    // ==================== GET /member/after-sale ====================

    /**
     * GET /member/after-sale — 获取当前用户售后列表及可售后商品候选项。
     * <p>
     * 响应结构：
     * <ul>
     *   <li>items — 当前用户的售后申请列表</li>
     *   <li>candidates — 当前用户订单中可申请售后的商品候选项</li>
     *   <li>serviceTypes — 可选售后类型（refund/exchange/repair）</li>
     *   <li>reasons — 可选售后原因</li>
     * </ul>
     */
    @GetMapping("/member/after-sale")
    public Object memberAfterSales(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(required = false) String orderId) {
        String uid = requireUserId(authHeader);
        JSONObject result = afterSaleService.queryAfterSales(uid, orderId, runtimeOrders);
        return FrontResponse.success(result);
    }

    // ==================== POST /member/after-sale ====================

    /**
     * POST /member/after-sale — 提交售后申请。
     * <p>
     * 请求体包含 orderId, skuId, type, reason, description。
     * 校验当前用户是否能对该订单发起售后。
     */
    @PostMapping("/member/after-sale")
    public Object submitAfterSale(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> params) {
        String uid = requireUserId(authHeader);

        String orderId = params.get("orderId") != null ? params.get("orderId").toString() : "";
        String skuId = params.get("skuId") != null ? params.get("skuId").toString() : "";
        if (orderId.isBlank() || skuId.isBlank()) {
            return FrontResponse.failure("售后商品不能为空");
        }

        String type = params.get("type") != null ? params.get("type").toString() : "refund";
        String reason = params.get("reason") != null ? params.get("reason").toString() : "其他";
        String description = params.get("description") != null ? params.get("description").toString() : "";

        try {
            JSONObject item = afterSaleService.submitAfterSale(
                    uid, orderId, skuId, type, reason, description,
                    runtimeOrders, featureCounter);
            afterSaleStore.save();
            return FrontResponse.success(item);
        } catch (IllegalArgumentException e) {
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
