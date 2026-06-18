package com.xtx.mock.store;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.support.MockJsonPersistence;
import com.xtx.mock.support.MockRuntimePaths;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RuntimeInviteStore — 邀请模块运行时存储。
 * <p>
 * 职责：
 * <ul>
 *   <li>管理 runtimeInvite 和 runtimeInviteBindings 两个内存态 Map（按 userId 隔离）</li>
 *   <li>文件持久化（data/user-invite-runtime.json 和 data/user-invite-bindings-runtime.json）</li>
 *   <li>默认/空邀请数据构建</li>
 *   <li>新用户空数据初始化</li>
 * </ul>
 */
@Slf4j
public class RuntimeInviteStore {

    /** 运行时邀请数据 (按需持久化), keyed by userId */
    private final Map<String, JSONObject> runtimeInvite = new ConcurrentHashMap<>();

    /** 运行时邀请绑定记录 (按需持久化), keyed by userId */
    private final Map<String, JSONArray> runtimeInviteBindings = new ConcurrentHashMap<>();

    private String defaultUserId;

    public RuntimeInviteStore(String defaultUserId) {
        this.defaultUserId = defaultUserId;
    }

    /**
     * 设置默认用户 ID（由 MockController 在 @PostConstruct 中调用）。
     */
    public void setDefaultUserId(String userId) {
        this.defaultUserId = userId;
    }

    // ==================== 邀请数据访问 ====================

    /**
     * 获取指定用户的邀请数据。如果首次访问且是默认用户，创建种子数据。
     */
    public JSONObject getInvite(String uid) {
        return runtimeInvite.computeIfAbsent(uid, this::buildDefaultInvite);
    }

    /**
     * 获取指定用户的邀请数据（不创建默认数据）。
     */
    public JSONObject getInviteRaw(String uid) {
        return runtimeInvite.get(uid);
    }

    /**
     * 获取全部的 invite map（用于查找邀请码）。
     */
    public Map<String, JSONObject> getInviteMap() {
        return runtimeInvite;
    }

    // ==================== 绑定记录访问 ====================

    /**
     * 获取绑定记录 Map（用于查找/遍历）。
     */
    public Map<String, JSONArray> getBindingsMap() {
        return runtimeInviteBindings;
    }

    // ==================== 数据初始化 ====================

    /**
     * 初始化新用户的邀请数据（空记录）。
     */
    public void initNewUserInvite(String userId) {
        runtimeInvite.computeIfAbsent(userId, this::buildEmptyInvite);
    }

    /**
     * 初始化默认用户的邀请数据（种子数据）。
     */
    public void ensureDefaultUserInvite(String userId) {
        runtimeInvite.computeIfAbsent(userId, this::buildDefaultInvite);
    }

    // ==================== 数据移除 ====================

    /**
     * 移除指定用户的所有邀请数据（用户注销时调用）。
     */
    public void removeUserData(String userId) {
        runtimeInvite.remove(userId);
        runtimeInviteBindings.remove(userId);
    }

    /**
     * 仅移除指定用户的邀请绑定记录，保留邀请信息（含邀请码）。
     * 用于积分重置场景：匹配原始 MockController 的 runtimeInviteBindings.remove(uid) 行为。
     */
    public void removeBindingsOnly(String userId) {
        runtimeInviteBindings.remove(userId);
    }

    // ==================== 构建数据 ====================

    /**
     * 为默认用户构建带种子记录的邀请数据。
     */
    private JSONObject buildDefaultInvite(String userId) {
        String code = defaultUserId.equals(userId) ? "XTX-001" : buildCode(userId);
        JSONArray records = new JSONArray();
        records.add(new JSONObject()
                .set("id", "invite_" + userId.hashCode() + "_1")
                .set("friendName", "新朋友A").set("status", "已注册")
                .set("rewardPoints", 60).set("createdAt", "2026-06-10 11:00:00"));
        records.add(new JSONObject()
                .set("id", "invite_" + userId.hashCode() + "_2")
                .set("friendName", "新朋友B").set("status", "已下单")
                .set("rewardPoints", 120).set("createdAt", "2026-06-12 16:40:00"));
        return new JSONObject()
                .set("inviteCode", code)
                .set("invitedCount", 2)
                .set("rewardPoints", 180)
                .set("records", records);
    }

    /**
     * 为普通用户构建空邀请数据。
     */
    private JSONObject buildEmptyInvite(String userId) {
        return new JSONObject()
                .set("inviteCode", buildCode(userId))
                .set("invitedCount", 0)
                .set("rewardPoints", 0)
                .set("records", new JSONArray());
    }

    /**
     * 生成邀请码。
     */
    public String buildCode(String userId) {
        return "XTX-" + (userId.hashCode() & 0x7FFF);
    }

    // ==================== 持久化 ====================

    /**
     * 从文件加载邀请和绑定数据。
     * 在服务启动时由 MockInviteController 构造器调用。
     */
    public void loadFromFile() {
        // 加载邀请数据
        JSONObject inviteObj = MockJsonPersistence.loadObject(MockRuntimePaths.USER_INVITE);
        if (inviteObj != null) {
            for (Map.Entry<String, Object> entry : inviteObj.entrySet()) {
                if (entry.getValue() instanceof JSONObject) {
                    runtimeInvite.put(entry.getKey(), (JSONObject) entry.getValue());
                }
            }
            log.info("Loaded invite data for {} users", runtimeInvite.size());
        }

        // 加载绑定记录
        JSONObject bindObj = MockJsonPersistence.loadObject(MockRuntimePaths.USER_INVITE_BINDINGS);
        if (bindObj != null) {
            for (Map.Entry<String, Object> entry : bindObj.entrySet()) {
                if (entry.getValue() instanceof JSONArray) {
                    runtimeInviteBindings.put(entry.getKey(), (JSONArray) entry.getValue());
                }
            }
            log.info("Loaded invite bindings for {} users", runtimeInviteBindings.size());
        }
    }

    /**
     * 保存所有用户的邀请数据到文件。
     */
    public void saveInvite() {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, JSONObject> entry : runtimeInvite.entrySet()) {
            obj.set(entry.getKey(), entry.getValue());
        }
        MockJsonPersistence.save(MockRuntimePaths.USER_INVITE, obj);
    }

    /**
     * 保存所有用户的绑定记录到文件。
     */
    public void saveBindings() {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, JSONArray> entry : runtimeInviteBindings.entrySet()) {
            obj.set(entry.getKey(), entry.getValue());
        }
        MockJsonPersistence.save(MockRuntimePaths.USER_INVITE_BINDINGS, obj);
    }
}
