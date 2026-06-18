package com.xtx.mock.controller;

import cn.hutool.json.JSONObject;
import com.xtx.mock.service.review.ReviewMockService;
import com.xtx.mock.store.RuntimeReviewStore;
import com.xtx.common.core.result.FrontResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MockReviewController — 评价模块控制器。
 * <p>
 * 职责：
 * <ul>
 *   <li>GET  /member/review                    — 获取当前用户评价列表</li>
 *   <li>POST /member/review                    — 提交评价</li>
 *   <li>GET  /goods/{id}/evaluate              — 获取商品详情评价摘要</li>
 *   <li>GET  /goods/{id}/evaluate/page         — 获取商品详情评价分页列表</li>
 *   <li>PUT  /goods/evaluate/{evaluateId}/praise  — 点赞/取消点赞</li>
 *   <li>POST /goods/evaluate/{evaluateId}/praise  — 点赞/取消点赞（POST 兼容）</li>
 * </ul>
 * <p>
 * 由 Spring 管理为 {@link RestController}，但运行时数据依赖
 * 由 {@link MockController} 在 {@code @PostConstruct} 中通过 setter 注入：
 * <ul>
 *   <li>{@link #setRuntimeTokens(Map)} — Token 映射（用于认证）</li>
 *   <li>{@link #setRuntimeOrders(Map)} — 订单数据（只读，用于校验和候选项构建）</li>
 *   <li>{@link #setRuntimeUsers(Map)} — 用户数据（只读，用于获取头像和昵称）</li>
 *   <li>{@link #setMasterSkus(JSONObject)} — SKU 主数据（只读）</li>
 *   <li>{@link #setMasterProducts(JSONObject)} — 商品主数据（只读）</li>
 *   <li>{@link #setMasterEvaluations(JSONObject)} — 预置评价数据（只读）</li>
 *   <li>{@link #setFeatureCounter(AtomicInteger)} — ID 生成器</li>
 * </ul>
 */
@Slf4j
@RestController
public class MockReviewController {

    private final RuntimeReviewStore reviewStore;
    private final ReviewMockService reviewService;

    // ==================== 注入的依赖引用 ====================

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /** MockController 的 runtimeOrders 引用（只读） */
    private Map<String, JSONObject> runtimeOrders;

    /** MockController 的 runtimeUsers 引用（只读） */
    private Map<String, JSONObject> runtimeUsers;

    /** MockController 的 masterSkus 引用（只读） */
    private JSONObject masterSkus;

    /** MockController 的 masterProducts 引用（只读） */
    private JSONObject masterProducts;

    /** MockController 的 masterEvaluations 引用（只读） */
    private JSONObject masterEvaluations;

    /** MockController 的 featureCounter 引用（ID 生成） */
    private AtomicInteger featureCounter;

    /**
     * 构造器：创建 Store 和 Service 并加载持久化数据。
     */
    public MockReviewController() {
        this.reviewStore = new RuntimeReviewStore();
        this.reviewService = new ReviewMockService(reviewStore);
        this.reviewStore.loadFromFile();
        log.info("MockReviewController initialized, store loaded from file");
    }

    // ==================== Setter 注入 ====================

    public void setRuntimeTokens(Map<String, String> tokens) {
        this.runtimeTokens = tokens;
    }

    public void setRuntimeOrders(Map<String, JSONObject> orders) {
        this.runtimeOrders = orders;
    }

    public void setRuntimeUsers(Map<String, JSONObject> users) {
        this.runtimeUsers = users;
    }

    public void setMasterSkus(JSONObject skus) {
        this.masterSkus = skus;
    }

    public void setMasterProducts(JSONObject products) {
        this.masterProducts = products;
    }

    public void setMasterEvaluations(JSONObject evaluations) {
        this.masterEvaluations = evaluations;
    }

    public void setFeatureCounter(AtomicInteger counter) {
        this.featureCounter = counter;
    }

    /**
     * 返回底层 Store 引用，供其他模块在重置/初始化时操作评价数据。
     */
    public RuntimeReviewStore getStore() {
        return reviewStore;
    }

    /**
     * 返回底层 Service 引用，供其他模块调用评价业务逻辑。
     */
    public ReviewMockService getService() {
        return reviewService;
    }

    // ==================== GET /member/review ====================

    /**
     * GET /member/review — 获取当前用户评价列表及候选项。
     * <p>
     * 响应结构：
     * <ul>
     *   <li>items — 当前用户的评价列表</li>
     *   <li>stats — 统计量（pending/completed）</li>
     * </ul>
     */
    @GetMapping("/member/review")
    public Object memberReviews(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(required = false) String orderId) {
        String uid = requireUserId(authHeader);
        JSONObject result = reviewService.queryMemberReviews(uid, orderId,
                runtimeOrders, masterSkus, masterProducts, featureCounter);
        return FrontResponse.success(result);
    }

    // ==================== POST /member/review ====================

    /**
     * POST /member/review — 提交评价。
     * <p>
     * 请求体包含 id, score, content, anonymous。
     * 提交后更新订单状态（所有 SKU 评价完成 → orderState 4→5）并同步到商品详情评价。
     */
    @PostMapping("/member/review")
    public Object submitReview(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> params) {
        String uid = requireUserId(authHeader);
        JSONObject result = reviewService.submitMemberReview(uid, params,
                runtimeOrders, runtimeUsers, masterSkus, masterProducts, featureCounter);
        if (result == null || result.isEmpty()) {
            return FrontResponse.failure("评价记录不存在");
        }
        return FrontResponse.success(result);
    }

    // ==================== GET /goods/{id}/evaluate ====================

    /**
     * GET /goods/{id}/evaluate — 获取商品详情评价摘要（统计信息）。
     */
    @GetMapping("/goods/{id}/evaluate")
    public Object goodsEvaluate(@PathVariable String id) {
        JSONObject result = reviewService.queryGoodsEvaluate(id, masterEvaluations);
        return FrontResponse.success(result);
    }

    // ==================== GET /goods/{id}/evaluate/page ====================

    /**
     * GET /goods/{id}/evaluate/page — 获取商品详情评价分页列表。
     * <p>
     * 支持 hasPicture/tag/sortField 筛选排序。
     */
    @GetMapping("/goods/{id}/evaluate/page")
    public Object goodsEvaluatePage(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String hasPicture,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String sortField) {
        JSONObject result = reviewService.queryGoodsEvaluatePage(
                id, page, pageSize, hasPicture, tag, sortField, masterEvaluations);
        return FrontResponse.success(result);
    }

    // ==================== PUT /goods/evaluate/{evaluateId}/praise ====================

    /**
     * PUT /goods/evaluate/{evaluateId}/praise — 点赞/取消点赞评价。
     * <p>
     * 请求体可选: { "isPraise": true/false }
     * 无请求体时切换点赞状态。
     */
    @PutMapping("/goods/evaluate/{evaluateId}/praise")
    public Object praiseEvaluate(
            @PathVariable String evaluateId,
            @RequestBody(required = false) JSONObject body) {
        JSONObject result = reviewService.togglePraise(evaluateId, body, masterEvaluations);
        return FrontResponse.success(result);
    }

    // ==================== POST /goods/evaluate/{evaluateId}/praise ====================

    /**
     * POST /goods/evaluate/{evaluateId}/praise — 兼容 POST 方式的点赞。
     */
    @PostMapping("/goods/evaluate/{evaluateId}/praise")
    public Object praiseEvaluatePost(
            @PathVariable String evaluateId,
            @RequestBody(required = false) JSONObject body) {
        JSONObject result = reviewService.togglePraise(evaluateId, body, masterEvaluations);
        return FrontResponse.success(result);
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
