package com.xtx.mock.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeMessageStore;
import lombok.extern.slf4j.Slf4j;

/**
 * MessageMockService — 站内消息业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>获取用户消息列表（含 summary: total / unread）</li>
 *   <li>标记消息已读（单条或全部）</li>
 *   <li>保持旧响应结构不变</li>
 *   <li>不直接读写文件，委托 RuntimeMessageStore 持久化</li>
 * </ul>
 * <p>
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockMessageController} 直接实例化。
 */
@Slf4j
public class MessageMockService {

    private final RuntimeMessageStore store;

    /**
     * @param store 消息运行时存储
     */
    public MessageMockService(RuntimeMessageStore store) {
        this.store = store;
    }

    // ==================== 获取消息列表 ====================

    /**
     * 获取用户消息列表，保持旧响应结构：
     * <pre>
     * {
     *   "summary": { "total": 3, "unread": "3" },
     *   "items": [ { "id": "msg_1", "title": "...", ... } ]
     * }
     * </pre>
     *
     * @param uid 当前用户 ID
     * @return 含 summary 和 items 的 JSONObject
     */
    public JSONObject getMessages(String uid) {
        JSONArray items = store.getMessages(uid);
        long unreadCount = items.stream()
                .map(o -> (JSONObject) o)
                .filter(item -> !item.getBool("isRead", false))
                .count();
        JSONObject result = new JSONObject();
        result.set("summary", new JSONObject()
                .set("total", items.size())
                .set("unread", unreadCount));
        result.set("items", items);
        return result;
    }

    // ==================== 标记已读 ====================

    /**
     * 标记消息已读。
     * <ul>
     *   <li>支持单条标记（指定 id）</li>
     *   <li>支持全部标记（readAll = true）</li>
     *   <li>标记后立即持久化</li>
     * </ul>
     *
     * @param uid     当前用户 ID
     * @param id      单条消息 ID（readAll 时忽略）
     * @param readAll 是否标记全部为已读
     */
    public void markRead(String uid, String id, boolean readAll) {
        if (readAll) {
            store.markAllAsRead(uid);
        } else {
            store.markAsRead(uid, id);
        }
        store.save();
    }

    // ==================== 新用户初始化 ====================

    /**
     * 为新用户初始化默认消息。
     *
     * @param userId 新用户 ID
     */
    public void initNewUserMessages(String userId) {
        store.initNewUserMessages(userId);
    }
}
