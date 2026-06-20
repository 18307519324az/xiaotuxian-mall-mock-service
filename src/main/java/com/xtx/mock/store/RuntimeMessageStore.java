package com.xtx.mock.store;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.support.MockJsonPersistence;
import com.xtx.mock.support.MockRuntimePaths;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RuntimeMessageStore — 站内消息运行时存储。
 * <p>
 * 职责：
 * <ul>
 *   <li>管理 runtimeMessages 内存态 Map（按 userId 隔离）</li>
 *   <li>文件持久化（data/user-messages-runtime.json）</li>
 *   <li>消息 ID 自增计数器管理</li>
 *   <li>新用户默认消息初始化</li>
 * </ul>
 * <p>
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockMessageController} 直接实例化。
 */
@Slf4j
public class RuntimeMessageStore {

    /** 运行时站内消息 (文件持久化), keyed by userId */
    private final Map<String, JSONArray> runtimeMessages = new ConcurrentHashMap<>();

    /** 消息 ID 计数器（不与 MockController.featureCounter 共享） */
    private final AtomicInteger msgIdCounter = new AtomicInteger(0);

    // ==================== 消息读取 ====================

    /**
     * 获取指定用户的消息列表。如果首次访问且无持久化数据，创建默认消息。
     */
    public JSONArray getMessages(String uid) {
        return runtimeMessages.computeIfAbsent(uid, k -> buildDefaultMessages());
    }

    // ==================== 新用户初始化 ====================

    /**
     * 为新用户初始化默认消息（仅当尚无消息时）。
     * 由 MockController.initNewUserFeatureData() 调用。
     */
    public void initNewUserMessages(String userId) {
        runtimeMessages.computeIfAbsent(userId, k -> buildDefaultMessages());
    }

    // ==================== 标记已读 ====================

    /**
     * 标记指定用户的单条消息为已读。
     */
    public void markAsRead(String uid, String id) {
        JSONArray items = runtimeMessages.get(uid);
        if (items == null) return;
        for (Object obj : items) {
            JSONObject item = (JSONObject) obj;
            if (id.equals(item.getStr("id"))) {
                item.set("isRead", true);
            }
        }
    }

    /**
     * 标记指定用户的所有消息为已读。
     */
    public void markAllAsRead(String uid) {
        JSONArray items = runtimeMessages.get(uid);
        if (items == null) return;
        for (Object obj : items) {
            ((JSONObject) obj).set("isRead", true);
        }
    }

    // ==================== 构建默认消息 ====================

    /**
     * 构造 3 条默认消息，所有消息默认未读。
     * 使用独立的 msgIdCounter 保证 ID 唯一。
     */
    private JSONArray buildDefaultMessages() {
        JSONArray arr = new JSONArray();
        arr.add(new JSONObject()
                .set("id", "msg_" + msgIdCounter.incrementAndGet())
                .set("title", "会员权益升级提醒")
                .set("content", "您的会员中心功能已升级，可查看积分、优惠券和礼品卡。")
                .set("type", "系统通知")
                .set("isRead", false)
                .set("createdAt", "2026-06-12 09:00:00"));
        arr.add(new JSONObject()
                .set("id", "msg_" + msgIdCounter.incrementAndGet())
                .set("title", "邀请有礼活动开始")
                .set("content", "分享邀请码给好友，好友下单后可获得双倍积分奖励。")
                .set("type", "活动通知")
                .set("isRead", false)
                .set("createdAt", "2026-06-11 15:30:00"));
        arr.add(new JSONObject()
                .set("id", "msg_" + msgIdCounter.incrementAndGet())
                .set("title", "订单服务提示")
                .set("content", "您可以在已完成订单中提交评价，或在售后页面发起服务申请。")
                .set("type", "服务通知")
                .set("isRead", false)
                .set("createdAt", "2026-06-10 12:10:00"));
        return arr;
    }

    // ==================== 持久化 ====================

    /**
     * 从文件加载消息数据，并计算当前最大消息编号。
     * 在服务启动时由 MockMessageController 构造器调用。
     */
    public void loadFromFile() {
        JSONObject obj = MockJsonPersistence.loadObject(MockRuntimePaths.USER_MESSAGES);
        if (obj != null) {
            for (Map.Entry<String, Object> entry : obj.entrySet()) {
                if (entry.getValue() instanceof JSONArray) {
                    runtimeMessages.put(entry.getKey(), (JSONArray) entry.getValue());
                }
            }
            // 计算当前最大消息编号
            for (Map.Entry<String, JSONArray> entry : runtimeMessages.entrySet()) {
                for (Object item : entry.getValue()) {
                    if (item instanceof JSONObject) {
                        String id = ((JSONObject) item).getStr("id", "");
                        if (id.startsWith("msg_")) {
                            try {
                                int num = Integer.parseInt(id.substring(4));
                                msgIdCounter.set(Math.max(msgIdCounter.get(), num));
                            } catch (Exception ignored) {
                                // non-numeric msg ID suffix, skip
                            }
                        }
                    }
                }
            }
            log.info("Loaded messages for {} users, max msg ID: {}", runtimeMessages.size(), msgIdCounter.get());
        }
    }

    /**
     * 保存所有用户的消息到文件。
     * 每次标记已读后由 MessageMockService 调用。
     */
    public void save() {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, JSONArray> entry : runtimeMessages.entrySet()) {
            obj.set(entry.getKey(), entry.getValue());
        }
        MockJsonPersistence.save(MockRuntimePaths.USER_MESSAGES, obj);
    }
}
