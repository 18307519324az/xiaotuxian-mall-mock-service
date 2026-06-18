package com.xtx.mock.controller;

import cn.hutool.json.JSONObject;
import com.xtx.mock.service.activity.UserActivityMockService;
import com.xtx.mock.store.RuntimeUserActivityStore;
import com.xtx.common.core.result.FrontResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * MockUserActivityController — 用户行为（收藏/浏览历史）控制器。
 * <p>
 * 职责：
 * <ul>
 *   <li>GET  /member/collect         — 收藏列表</li>
 *   <li>POST /member/collect         — 添加收藏</li>
 *   <li>DELETE /member/collect       — 取消收藏</li>
 *   <li>GET  /member/history         — 浏览历史列表</li>
 *   <li>POST /member/history         — 添加浏览历史</li>
 *   <li>DELETE /member/history       — 清空浏览历史</li>
 * </ul>
 * <p>
 * 由 Spring 管理为 {@link RestController}，但运行时数据依赖
 * 由 {@link com.xtx.mock.controller.MockController} 在 {@code @PostConstruct} 中通过 setter 注入：
 * <ul>
 *   <li>{@link #setRuntimeTokens(Map)} — Token 映射（用于认证）</li>
 *   <li>{@link #setMasterProducts(JSONObject)} — 商品主数据（只读）</li>
 *   <li>{@link #setCuratedDemoProducts(JSONObject)} — 精选演示商品（只读）</li>
 * </ul>
 */
@Slf4j
@RestController
public class MockUserActivityController {

    private final RuntimeUserActivityStore activityStore;
    private final UserActivityMockService activityService;

    // ==================== 注入的依赖引用 ====================

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /** MockController 的 masterProducts 引用（只读） */
    private JSONObject masterProducts;

    /** MockController 的 curatedDemoProducts 引用（只读） */
    private JSONObject curatedDemoProducts;

    /**
     * 构造器：创建 Store 和 Service 并加载持久化数据。
     */
    public MockUserActivityController() {
        this.activityStore = new RuntimeUserActivityStore();
        this.activityService = new UserActivityMockService(activityStore);
        log.info("MockUserActivityController initialized");
    }

    // ==================== Setter 注入 ====================

    public void setRuntimeTokens(Map<String, String> tokens) {
        this.runtimeTokens = tokens;
    }

    public void setMasterProducts(JSONObject products) {
        this.masterProducts = products;
    }

    public void setCuratedDemoProducts(JSONObject products) {
        this.curatedDemoProducts = products;
    }

    /**
     * 返回底层 Store 引用，供其他模块在重置/初始化时操作用户行为数据。
     */
    public RuntimeUserActivityStore getStore() {
        return activityStore;
    }

    /**
     * 返回底层 Service 引用，供其他模块调用用户行为业务逻辑。
     */
    public UserActivityMockService getService() {
        return activityService;
    }

    // ==================== GET /member/collect ====================

    /**
     * GET /member/collect — 收藏列表（按 userId 隔离，支持分页）。
     */
    @GetMapping("/member/collect")
    public Object collectList(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(defaultValue = "1") Integer collectType) {
        String uid = requireUserId(authHeader);
        JSONObject result = activityService.queryCollectList(uid, page, pageSize, collectType,
                masterProducts, curatedDemoProducts);
        return FrontResponse.success(result);
    }

    // ==================== POST /member/collect ====================

    /**
     * POST /member/collect — 添加收藏。
     */
    @PostMapping("/member/collect")
    public Object collectAdd(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> params) {
        String uid = requireUserId(authHeader);
        String goodsId = params.get("goodsId") != null ? params.get("goodsId").toString() : null;
        if (goodsId == null) return FrontResponse.failure("缺少 goodsId");

        activityService.addCollect(uid, goodsId);
        return FrontResponse.success(Boolean.TRUE);
    }

    // ==================== DELETE /member/collect ====================

    /**
     * DELETE /member/collect — 取消收藏。
     * 支持按 goodsId 或 ids（JSON 数组字符串）取消。
     */
    @DeleteMapping("/member/collect")
    public Object collectDelete(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> params) {
        String uid = requireUserId(authHeader);
        String goodsId = params.get("goodsId") != null ? params.get("goodsId").toString() : null;
        String ids = params.get("ids") != null ? params.get("ids").toString() : null;

        if (goodsId != null) {
            activityService.removeCollectByGoodsId(uid, goodsId);
        } else if (ids != null) {
            activityService.removeCollectByIds(uid, ids);
        }
        return FrontResponse.success(Boolean.TRUE);
    }

    // ==================== GET /member/history ====================

    /**
     * GET /member/history — 足迹列表（分页，按 userId 隔离）。
     */
    @GetMapping("/member/history")
    public Object historyList(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        String uid = requireUserId(authHeader);
        JSONObject result = activityService.queryHistoryList(uid, page, pageSize,
                masterProducts, curatedDemoProducts);
        return FrontResponse.success(result);
    }

    // ==================== POST /member/history ====================

    /**
     * POST /member/history — 添加足迹（去重，限 100 条）。
     */
    @PostMapping("/member/history")
    public Object historyAdd(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> params) {
        String uid = requireUserId(authHeader);
        String goodsId = params.get("goodsId") != null ? params.get("goodsId").toString() : null;
        if (goodsId == null) return FrontResponse.success(Boolean.TRUE);

        activityService.addHistory(uid, goodsId);
        return FrontResponse.success(Boolean.TRUE);
    }

    // ==================== DELETE /member/history ====================

    /**
     * DELETE /member/history — 清空足迹。
     */
    @DeleteMapping("/member/history")
    public Object historyClear(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        activityService.clearHistory(uid);
        return FrontResponse.success(Boolean.TRUE);
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
