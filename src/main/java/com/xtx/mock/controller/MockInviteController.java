package com.xtx.mock.controller;

import cn.hutool.json.JSONObject;
import com.xtx.mock.service.invite.InviteMockService;
import com.xtx.mock.service.points.PointsMockService;
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
 * MockInviteController — 邀请模块控制器。
 * <p>
 * 职责：
 * <ul>
 *   <li>GET  /member/invite      — 获取当前用户邀请信息</li>
 *   <li>POST /member/invite/bind  — 绑定邀请码（发放奖励积分）</li>
 * </ul>
 * <p>
 * 由 Spring 管理为 {@link RestController}，但运行时数据依赖
 * 由 {@link MockController} 在 {@code @PostConstruct} 中通过 setter 注入：
 * <ul>
 *   <li>{@link #setRuntimeTokens(Map)} — Token 映射</li>
 *   <li>{@link #setRuntimeUsers(Map)} — 用户数据（用于读取 nickname/account）</li>
 *   <li>{@link #setRuntimePoints(Map)} — 积分数据引用（发放奖励积分）</li>
 *   <li>{@link #setFeatureCounter(AtomicInteger)} — ID 生成器</li>
 *   <li>{@link #setSavePointsCallback(Runnable)} — 触发积分持久化</li>
 * </ul>
 * <p>
 * MockController 通过 {@link #getStore()} 和 {@link #getService()} 访问底层 Store/Service，
 * 用于用户注册/注销时初始化或清理邀请数据，以及注册时桥接邀请绑定逻辑。
 */
@Slf4j
@RestController
public class MockInviteController {

    private final RuntimeInviteStore inviteStore;
    private final InviteMockService inviteService;

    // ==================== 注入的依赖引用 ====================

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /** MockController 的 runtimeUsers 引用（用于读取 nickname/account） */
    private Map<String, JSONObject> runtimeUsers;

    /** PointsMockService 引用（发放奖励积分、积分汇总） */
    private PointsMockService pointsService;

    /** RuntimePointsStore 引用（积分持久化） */
    private RuntimePointsStore pointsStore;

    /** MockController 的 featureCounter 引用（ID 生成） */
    private AtomicInteger featureCounter;

    /**
     * 构造器：创建 Store 和 Service 并加载持久化数据。
     * Spring 实例化时自动调用。
     * defaultUserId 由 MockController 在 @PostConstruct 中通过 setDefaultUserId 设置。
     */
    public MockInviteController() {
        this.inviteStore = new RuntimeInviteStore(null);
        this.inviteService = new InviteMockService(inviteStore);
        this.inviteStore.loadFromFile();
        log.info("MockInviteController initialized, store loaded from file");
    }

    // ==================== Setter 注入 ====================

    public void setRuntimeTokens(Map<String, String> tokens) {
        this.runtimeTokens = tokens;
    }

    public void setRuntimeUsers(Map<String, JSONObject> users) {
        this.runtimeUsers = users;
    }

    public void setPointsService(PointsMockService service) {
        this.pointsService = service;
    }

    public void setPointsStore(RuntimePointsStore store) {
        this.pointsStore = store;
    }

    public void setFeatureCounter(AtomicInteger counter) {
        this.featureCounter = counter;
    }

    /**
     * 设置默认用户 ID（由 MockController 在 @PostConstruct 中调用）。
     * 用于确保默认用户的邀请码为 "XTX-001"。
     */
    public void setDefaultUserId(String userId) {
        this.inviteStore.setDefaultUserId(userId);
    }

    /**
     * 返回底层 InviteStore 引用，供 MockController 在用户注册/注销时初始化或清理数据。
     */
    public RuntimeInviteStore getStore() {
        return inviteStore;
    }

    /**
     * 返回底层 InviteService 引用，供 MockController 在注册时桥接邀请绑定逻辑。
     */
    public InviteMockService getService() {
        return inviteService;
    }

    // ==================== GET /member/invite ====================

    /**
     * GET /member/invite — 获取当前用户邀请信息。
     * <p>
     * 响应包含 inviteCode, invitedCount, rewardPoints, records。
     * 返回前 enrichment：补全邀请记录中的可读名称。
     */
    @GetMapping("/member/invite")
    public Object memberInvite(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        JSONObject inviteData = inviteStore.getInvite(uid);
        inviteService.enrichInviteRecords(inviteData, runtimeUsers);
        return FrontResponse.success(inviteData);
    }

    // ==================== POST /member/invite/bind ====================

    /**
     * POST /member/invite/bind — 绑定邀请码。
     * <p>
     * 流程：
     * <ol>
     *   <li>校验邀请码参数</li>
     *   <li>查找邀请码所属用户</li>
     *   <li>自邀检查</li>
     *   <li>重复绑定检查（同一邀请人 + 已被其他用户绑定）</li>
     *   <li>执行绑定（写入记录、更新统计）</li>
     *   <li>发放奖励积分（邀请人 +120, 被邀请人 +20）</li>
     *   <li>持久化积分</li>
     * </ol>
     */
    @PostMapping("/member/invite/bind")
    public Object bindInvite(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> params) {
        String uid = requireUserId(authHeader);

        // 1. 解析邀请码
        String code = params.get("code") != null ? params.get("code").toString().trim().toUpperCase() : "";
        if (code.isEmpty()) {
            return FrontResponse.failure("邀请码不能为空");
        }

        // 2. 查找邀请人
        String inviterId = inviteService.findInviterByCode(code);
        if (inviterId == null) {
            return FrontResponse.failure("邀请码无效");
        }

        // 3. 自邀检查
        if (inviterId.equals(uid)) {
            return FrontResponse.failure(40900, "不能绑定自己的邀请码");
        }

        // 4a. 检查同一邀请人是否已绑定过该 invitee
        if (inviteService.isAlreadyInvitedBy(inviterId, uid)) {
            return FrontResponse.failure(40900, "该好友已绑定邀请关系，不能重复领取奖励");
        }

        // 4b. 检查 invitee 是否已被其他用户绑定
        if (inviteService.isInviteeAlreadyBound(uid)) {
            return FrontResponse.failure(40900, "该好友已被其他用户绑定，不能重复绑定");
        }

        // 5. 执行绑定（写入绑定记录 + 更新邀请统计）
        JSONObject bindResult = inviteService.executeBind(uid, inviterId, runtimeUsers, featureCounter);
        JSONObject inviterInvite = bindResult.getJSONObject("inviterInvite");
        String bindNow = bindResult.getStr("bindNow");

        // 6. 发放奖励积分（通过 PointsMockService）
        String inviterCode = inviterInvite.getStr("inviteCode", "");
        if (pointsService != null) {
            // 邀请人 +120 有效期 90天
            pointsService.award(inviterId, 120, "邀请好友奖励（" + inviterCode + "）", "invite", 90, featureCounter);
            // 被邀请人 +20 有效期 30天
            pointsService.award(uid, 20, "新用户绑定邀请码奖励", "invite", 30, featureCounter);
            // 重新计算积分汇总
            pointsService.computeSummary(pointsStore.getPoints(inviterId));
            pointsService.computeSummary(pointsStore.getPoints(uid));
        }

        // 7. 持久化（pointsStore / inviteStore 各自持久化）
        if (pointsStore != null) pointsStore.save();
        inviteStore.saveInvite();
        inviteStore.saveBindings();

        log.info("Invite binding: inviter={} invitee={} code={}", inviterId, uid, code);
        return FrontResponse.success(new JSONObject()
                .set("message", "邀请码绑定成功")
                .set("rewardPoints", 120)
                .set("bound", true)
                .set("rewarded", true));
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
