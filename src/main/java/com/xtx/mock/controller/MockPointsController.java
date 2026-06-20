package com.xtx.mock.controller;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.service.points.PointsMockService;
import com.xtx.mock.store.RuntimeGameStore;
import com.xtx.mock.store.RuntimeInviteStore;
import com.xtx.mock.store.RuntimePointsStore;
import com.xtx.common.core.result.FrontResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MockPointsController — 积分模块控制器。
 * <p>
 * 职责：
 * <ul>
 *   <li>GET  /member/points      — 获取当前用户积分信息</li>
 *   <li>POST /member/points/reset — 重置当前用户积分（含游戏和邀请数据清理）</li>
 * </ul>
 * <p>
 * 由 Spring 管理为 {@link RestController}，但运行时数据依赖
 * 由 {@link MockController} 在 {@code @PostConstruct} 中通过 setter 注入：
 * <ul>
 *   <li>{@link #setRuntimeTokens(Map)} — Token 映射</li>
 *   <li>{@link #setFeatureCounter(AtomicInteger)} — ID 生成器</li>
 *   <li>{@link #setGameStore(RuntimeGameStore)} — 游戏 Store（用于 signedToday 检查和 reset）</li>
 *   <li>{@link #setInviteStore(RuntimeInviteStore)} — 邀请 Store（用于 reset 清理）</li>
 * </ul>
 */
@Slf4j
@RestController
public class MockPointsController {

    private final RuntimePointsStore pointsStore;
    private final PointsMockService pointsService;

    // ==================== 注入的依赖引用 ====================

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /** MockController 的 featureCounter 引用（ID 生成） */
    private AtomicInteger featureCounter;

    /** MockGameController 的 RuntimeGameStore 引用（用于 signedToday 检查 + reset 清理） */
    private RuntimeGameStore gameStore;

    /** MockInviteController 的 RuntimeInviteStore 引用（用于 reset 清理） */
    private RuntimeInviteStore inviteStore;

    /**
     * 构造器：创建 Store 和 Service 并加载持久化数据。
     * Spring 实例化时自动调用。
     * defaultUserId 由 MockController 在 @PostConstruct 中通过 setDefaultUserId 设置。
     */
    public MockPointsController() {
        this.pointsStore = new RuntimePointsStore(null);
        this.pointsService = new PointsMockService(pointsStore);
        this.pointsStore.loadFromFile();
        log.info("MockPointsController initialized, store loaded from file");
    }

    // ==================== Setter 注入 ====================

    public void setRuntimeTokens(Map<String, String> tokens) {
        this.runtimeTokens = tokens;
    }

    public void setFeatureCounter(AtomicInteger counter) {
        this.featureCounter = counter;
    }

    public void setGameStore(RuntimeGameStore store) {
        this.gameStore = store;
    }

    public void setInviteStore(RuntimeInviteStore store) {
        this.inviteStore = store;
    }

    /**
     * 设置默认用户 ID（由 MockController 在 @PostConstruct 中调用）。
     * 用于确保默认用户的积分种子数据。
     */
    public void setDefaultUserId(String userId) {
        this.pointsStore.setDefaultUserId(userId);
    }

    /**
     * 返回底层 PointsStore 引用，供其他模块在注册/注销时操作积分数据。
     */
    public RuntimePointsStore getStore() {
        return pointsStore;
    }

    /**
     * 返回底层 PointsMockService 引用，供其他模块调用积分业务逻辑。
     */
    public PointsMockService getService() {
        return pointsService;
    }

    // ==================== GET /member/points ====================

    /**
     * GET /member/points — 获取当前用户积分信息。
     * <p>
     * 响应包含 balance, level, levelName, nextLevelNeed, expiringSoon, signedToday, records, rules。
     */
    @GetMapping("/member/points")
    public Object memberPoints(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        JSONObject points = pointsStore.getPoints(uid);
        pointsService.computeSummary(points);

        // 判断今日是否已签到（通过 GameStore 签名记录）
        String today = java.time.LocalDateTime.now().toString().substring(0, 10);
        boolean signedToday = false;
        if (gameStore != null) {
            JSONArray userSignins = gameStore.getSigninRecords(uid);
            for (int i = 0; i < userSignins.size(); i++) {
                JSONObject rec = userSignins.getJSONObject(i);
                if (today.equals(rec.getStr("date"))) {
                    signedToday = true;
                    break;
                }
            }
        }
        // fallback: also check points records
        if (!signedToday) {
            JSONArray records = points.getJSONArray("records");
            if (records != null) {
                for (int i = 0; i < records.size(); i++) {
                    JSONObject rec = records.getJSONObject(i);
                    String reason = rec.getStr("reason", "");
                    String source = rec.getStr("source", "");
                    if ("sign".equals(reason) || "signin".equals(source)) {
                        String createdAt = rec.getStr("createdAt", "");
                        if (createdAt.startsWith(today)) {
                            signedToday = true;
                            break;
                        }
                    }
                }
            }
        }
        points.set("signedToday", signedToday);
        return FrontResponse.success(points);
    }

    // ==================== POST /member/points/reset ====================

    /**
     * POST /member/points/reset — 重置当前用户的积分演示数据（仅限本地演示）。
     * <p>
     * 清空积分明细、签到记录、邀请绑定记录，积分归零。
     * 同时清理游戏数据（签到记录）和邀请数据，保持旧行为。
     */
    @PostMapping("/member/points/reset")
    public Object resetPoints(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> params) {
        String uid = requireUserId(authHeader);

        // 清空该用户的积分记录
        pointsStore.resetPoints(uid);

        // 清理游戏数据（签到记录、抽奖历史）
        if (gameStore != null) {
            gameStore.removeUserData(uid);
        }

        // 清理邀请绑定记录（保留邀请码，匹配原始行为）
        if (inviteStore != null) {
            inviteStore.removeBindingsOnly(uid);
        }

        // 持久化积分
        pointsStore.save();

        return FrontResponse.success(new JSONObject()
                .set("balance", 0)
                .set("level", "普通会员")
                .set("expiringSoon", 0)
                .set("records", new JSONArray())
                .set("message", "积分演示数据已重置"));
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
