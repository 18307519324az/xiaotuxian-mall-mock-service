package com.xtx.mock.controller;

import cn.hutool.json.JSONArray;
import com.xtx.common.core.result.FrontResponse;
import com.xtx.mock.service.preference.UserPreferenceMockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * MockUserPreferenceController — 用户偏好（品牌关注/专题收藏）控制器。
 * <p>
 * 职责：Brand-follow、Topic-collect 相关读写接口，共 6 个端点。
 * <p>
 * 数据源由 {@link com.xtx.mock.controller.MockController} 在 {@code @PostConstruct}
 * 中通过 setter 注入。
 * <ul>
 *   <li>品牌关注数据由本模块独立管理（内存 Map + 文件持久化）</li>
 *   <li>专题收藏数据通过函数引用委托到
 *       {@link com.xtx.mock.store.RuntimeUserActivityStore}</li>
 * </ul>
 */
@Slf4j
@RestController
public class MockUserPreferenceController {

    private final UserPreferenceMockService prefService;

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    public MockUserPreferenceController() {
        this.prefService = new UserPreferenceMockService();
        log.info("MockUserPreferenceController initialized");
    }

    /** 返回底层 Service 引用，供其他模块只读调用。 */
    public UserPreferenceMockService getService() {
        return prefService;
    }

    // ==================== Setter 注入 ====================

    public void setRuntimeTokens(Map<String, String> tokens) {
        this.runtimeTokens = tokens;
    }

    public void setHomeBrandData(JSONArray v) {
        prefService.setHomeBrandData(v);
    }

    public void setHomeSpecialData(JSONArray v) {
        prefService.setHomeSpecialData(v);
    }

    public void setBrandFollowRuntimeFile(String v) {
        prefService.setBrandFollowRuntimeFile(v);
    }

    public void setDefaultUserId(String v) {
        prefService.setDefaultUserId(v);
    }

    public void setTopicCollectGetter(Function<String, Set<String>> v) {
        prefService.setTopicCollectGetter(v);
    }

    public void setTopicCollectAdder(BiConsumer<String, String> v) {
        prefService.setTopicCollectAdder(v);
    }

    public void setTopicCollectRemover(BiConsumer<String, String> v) {
        prefService.setTopicCollectRemover(v);
    }

    public void setTopicCollectSaver(Runnable v) {
        prefService.setTopicCollectSaver(v);
    }

    // ========================================================================
    //  Brand-follow 品牌关注
    // ========================================================================

    /**
     * GET /member/brand-follow — 品牌关注列表（分页）。
     */
    @GetMapping("/member/brand-follow")
    public Object brandFollowList(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        String uid = requireUserId(authHeader);
        return FrontResponse.success(prefService.buildBrandFollowList(uid, page, pageSize));
    }

    /**
     * POST /member/brand-follow/{id} — 关注品牌。
     */
    @PostMapping("/member/brand-follow/{id}")
    public Object brandFollowAdd(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String id) {
        String uid = requireUserId(authHeader);
        prefService.addBrandFollow(uid, id);
        return FrontResponse.success(null);
    }

    /**
     * DELETE /member/brand-follow/{id} — 取消关注品牌。
     */
    @DeleteMapping("/member/brand-follow/{id}")
    public Object brandFollowDelete(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String id) {
        String uid = requireUserId(authHeader);
        prefService.removeBrandFollow(uid, id);
        return FrontResponse.success(null);
    }

    // ========================================================================
    //  Topic-collect 专题收藏
    // ========================================================================

    /**
     * GET /member/topic-collect — 专题收藏列表（分页）。
     */
    @GetMapping("/member/topic-collect")
    public Object topicCollectList(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        String uid = requireUserId(authHeader);
        return FrontResponse.success(prefService.buildTopicCollectList(uid, page, pageSize));
    }

    /**
     * POST /member/topic-collect/{id} — 收藏专题。
     */
    @PostMapping("/member/topic-collect/{id}")
    public Object topicCollectAdd(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String id) {
        String uid = requireUserId(authHeader);
        prefService.addTopicCollect(uid, id);
        return FrontResponse.success(null);
    }

    /**
     * DELETE /member/topic-collect/{id} — 取消收藏专题。
     */
    @DeleteMapping("/member/topic-collect/{id}")
    public Object topicCollectDelete(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String id) {
        String uid = requireUserId(authHeader);
        prefService.removeTopicCollect(uid, id);
        return FrontResponse.success(null);
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
     * 从 Authorization header 提取用户 ID。
     * header 格式: "Bearer {token}"，返回 token 对应的 userId，未找到返回 null。
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
