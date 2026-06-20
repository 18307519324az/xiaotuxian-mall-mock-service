package com.xtx.mock.store;

import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RuntimeOrderStore — 订单运行时存储适配层。
 * <p>
 * 职责：
 * <ul>
 *   <li>持有 MockController 的 runtimeOrders 引用</li>
 *   <li>按 userId 隔离查询订单列表</li>
 *   <li>根据 orderId 查询/更新/删除订单</li>
 *   <li>委托 saveOrders 到 MockController（通过 Runnable 回调）</li>
 * </ul>
 * <p>
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockOrderQueryController} 和
 * {@code com.xtx.mock.controller.MockOrderActionController} 直接实例化，
 * 由 MockController 在 {@code @PostConstruct} 中通过 setter 注入运行时数据。
 */
@Slf4j
public class RuntimeOrderStore {

    /** MockController 的 runtimeOrders 引用（@PostConstruct 注入） */
    private Map<String, JSONObject> runtimeOrders;

    /** MockController 的 saveOrders 回调 */
    private Runnable saveOrdersCallback;

    public RuntimeOrderStore() {
    }

    // ==================== Setter 注入 ====================

    public void setRuntimeOrders(Map<String, JSONObject> orders) {
        this.runtimeOrders = orders;
    }

    public void setSaveOrdersCallback(Runnable callback) {
        this.saveOrdersCallback = callback;
    }

    // ==================== 读操作 ====================

    /**
     * 根据 orderId 获取订单。
     */
    public JSONObject getById(String orderId) {
        if (runtimeOrders == null) return null;
        return runtimeOrders.get(orderId);
    }

    /**
     * 根据 orderNo 获取订单。
     */
    public JSONObject getByOrderNo(String orderNo) {
        if (runtimeOrders == null || orderNo == null) return null;
        for (JSONObject order : runtimeOrders.values()) {
            if (orderNo.equals(order.getStr("orderNo"))) {
                return order;
            }
        }
        return null;
    }

    /**
     * 根据 orderNo 获取 orderId。
     */
    public String getIdByOrderNo(String orderNo) {
        if (runtimeOrders == null || orderNo == null) return null;
        for (Map.Entry<String, JSONObject> entry : runtimeOrders.entrySet()) {
            if (orderNo.equals(entry.getValue().getStr("orderNo"))) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 获取当前用户的所有订单（用于列表查询）。
     */
    public Collection<JSONObject> getAll() {
        if (runtimeOrders == null) return java.util.Collections.emptyList();
        return runtimeOrders.values();
    }

    /**
     * 返回 entrySet 用于迭代。
     */
    public Set<Map.Entry<String, JSONObject>> entrySet() {
        if (runtimeOrders == null) return java.util.Collections.emptySet();
        return runtimeOrders.entrySet();
    }

    /**
     * 委托保存订单（由 MockController 的 saveOrders 处理）。
     */
    public void saveOrders() {
        if (saveOrdersCallback != null) {
            saveOrdersCallback.run();
        }
    }

    /**
     * 判断订单是否属于指定用户。
     */
    public boolean isOrderOwner(JSONObject order, String userId) {
        if (order == null || userId == null) return false;
        return userId.equals(order.getStr("userId", ""));
    }

    // ==================== 写操作 ====================

    /**
     * 更新（或新增）运行时订单。
     */
    public void put(String orderId, JSONObject order) {
        if (runtimeOrders == null) return;
        runtimeOrders.put(orderId, order);
    }

    /**
     * 从运行时存储中移除指定订单。
     */
    public void remove(String orderId) {
        if (runtimeOrders == null) return;
        runtimeOrders.remove(orderId);
    }

    /**
     * 返回运行时订单 Map 当前大小。
     */
    public int size() {
        if (runtimeOrders == null) return 0;
        return runtimeOrders.size();
    }
}
