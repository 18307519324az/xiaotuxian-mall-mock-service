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
 * RuntimeCustomerServiceStore — 客服聊天和工单运行时存储。
 * <p>
 * 职责：
 * <ul>
 *   <li>管理 runtimeCustomerChats / runtimeCustomerTickets 内存态 Map</li>
 *   <li>文件持久化（user-customer-service-chats-runtime.json / user-customer-service-tickets-runtime.json）</li>
 *   <li>工单 ID 自增计数器管理</li>
 * </ul>
 * <p>
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockCustomerServiceController} 直接实例化。
 */
@Slf4j
public class RuntimeCustomerServiceStore {

    /** 运行时客服聊天记录 (文件持久化), keyed by userId */
    private final Map<String, JSONArray> runtimeCustomerChats = new ConcurrentHashMap<>();

    /** 运行时客服工单 (文件持久化), keyed by userId */
    private final Map<String, JSONArray> runtimeCustomerTickets = new ConcurrentHashMap<>();

    /** 工单 ID 计数器 */
    private final AtomicInteger ticketIdCounter = new AtomicInteger(0);

    // ==================== Chats ====================

    /**
     * 获取指定用户的聊天记录，不存在时创建空数组。
     */
    public JSONArray getChats(String uid) {
        return runtimeCustomerChats.computeIfAbsent(uid, k -> new JSONArray());
    }

    /**
     * 向指定用户的聊天记录添加一条消息。
     */
    public void addChatMessage(String uid, JSONObject message) {
        JSONArray chats = runtimeCustomerChats.computeIfAbsent(uid, k -> new JSONArray());
        chats.add(message);
    }

    // ==================== Tickets ====================

    /**
     * 获取指定用户的工单列表。
     */
    public JSONArray getTickets(String uid) {
        return runtimeCustomerTickets.getOrDefault(uid, new JSONArray());
    }

    /**
     * 向指定用户添加一条工单。
     */
    public void addTicket(String uid, JSONObject ticket) {
        JSONArray tickets = runtimeCustomerTickets.computeIfAbsent(uid, k -> new JSONArray());
        tickets.add(ticket);
    }

    /**
     * 自增并获取下一个工单编号。
     */
    public int incrementAndGetTicketId() {
        return ticketIdCounter.incrementAndGet();
    }

    // ==================== 持久化 ====================

    /**
     * 从文件加载客服聊天记录和工单数据，并计算最大工单编号。
     * 在服务启动时调用。
     */
    public void loadFromFiles() {
        loadChatsFromFile();
        loadTicketsFromFile();
    }

    /** 从文件加载客服聊天记录 */
    private void loadChatsFromFile() {
        JSONObject obj = MockJsonPersistence.loadObject(MockRuntimePaths.USER_CUSTOMER_SERVICE_CHATS);
        if (obj != null) {
            for (Map.Entry<String, Object> entry : obj.entrySet()) {
                if (entry.getValue() instanceof JSONArray) {
                    runtimeCustomerChats.put(entry.getKey(), (JSONArray) entry.getValue());
                }
            }
            log.info("Loaded customer service chats for {} users", runtimeCustomerChats.size());
        }
    }

    /** 从文件加载客服工单并计算最大编号 */
    private void loadTicketsFromFile() {
        JSONObject obj = MockJsonPersistence.loadObject(MockRuntimePaths.USER_CUSTOMER_SERVICE_TICKETS);
        if (obj != null) {
            for (Map.Entry<String, Object> entry : obj.entrySet()) {
                if (entry.getValue() instanceof JSONArray) {
                    runtimeCustomerTickets.put(entry.getKey(), (JSONArray) entry.getValue());
                }
            }
            // 计算当前最大工单编号
            for (Map.Entry<String, JSONArray> entry : runtimeCustomerTickets.entrySet()) {
                for (Object item : entry.getValue()) {
                    if (item instanceof JSONObject) {
                        JSONObject ticket = (JSONObject) item;
                        String id = ticket.getStr("id", "");
                        if (id.startsWith("ticket_")) {
                            try {
                                int num = Integer.parseInt(id.substring(7));
                                ticketIdCounter.set(Math.max(ticketIdCounter.get(), num));
                            } catch (Exception ignored) {
                                // non-numeric ticket id suffix, skip
                            }
                        }
                    }
                }
            }
            log.info("Loaded customer service tickets for {} users, max ticket ID: {}",
                    runtimeCustomerTickets.size(), ticketIdCounter.get());
        }
    }

    /** 保存客服聊天记录到文件 */
    public void saveChats() {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, JSONArray> entry : runtimeCustomerChats.entrySet()) {
            obj.set(entry.getKey(), entry.getValue());
        }
        MockJsonPersistence.save(MockRuntimePaths.USER_CUSTOMER_SERVICE_CHATS, obj);
    }

    /** 保存客服工单到文件 */
    public void saveTickets() {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, JSONArray> entry : runtimeCustomerTickets.entrySet()) {
            obj.set(entry.getKey(), entry.getValue());
        }
        MockJsonPersistence.save(MockRuntimePaths.USER_CUSTOMER_SERVICE_TICKETS, obj);
    }
}
