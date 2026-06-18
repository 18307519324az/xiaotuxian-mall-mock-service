package com.xtx.mock.service.order;

import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeOrderStore;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;

/**
 * OrderActionMockService — 订单状态变更业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>取消订单（state 1 → 6）</li>
 *   <li>删除订单（软/硬删除）</li>
 *   <li>模拟发货（state 2 → 3，含物流信息）</li>
 *   <li>确认收货（state 3 → 4）</li>
 * </ul>
 * <p>
 * 不修改非自身归属的订单。不依赖 Spring，由
 * {@code com.xtx.mock.controller.MockOrderActionController} 直接实例化。
 */
@Slf4j
public class OrderActionMockService {

    private final RuntimeOrderStore orderStore;

    public OrderActionMockService(RuntimeOrderStore orderStore) {
        this.orderStore = orderStore;
    }

    // ==================== 取消订单 ====================

    /**
     * 取消订单。
     * <p>
     * 规则：仅 orderState === 1（待付款）可取消 → 状态变为 6（已取消）。
     *
     * @param orderId      订单 ID
     * @param uid          用户 ID（用于所有权校验）
     * @param cancelReason 取消原因（可选，默认为"用户主动取消"）
     * @return 订单对象（成功）或 null（订单不存在/不属于该用户）
     * @throws IllegalStateException 如果订单状态不允许取消
     */
    public JSONObject cancel(String orderId, String uid, String cancelReason) {
        JSONObject order = orderStore.getById(orderId);
        if (!orderStore.isOrderOwner(order, uid)) {
            return null;
        }

        int state = order.getInt("orderState", 0);
        if (state != 1) {
            throw new IllegalStateException("当前订单状态不允许取消，仅待付款订单可取消");
        }

        order.set("orderState", 6);
        String reason = (cancelReason != null && !cancelReason.isBlank())
                ? cancelReason : "用户主动取消";
        order.set("cancelReason", reason);
        String now = LocalDateTime.now().toString();
        order.set("cancelTime", now);
        order.set("closeTime", now);

        // 持久化
        orderStore.put(orderId, order);
        orderStore.saveOrders();

        return order;
    }

    // ==================== 删除订单 ====================

    /**
     * 删除订单（从运行时存储中移除）。
     * <p>
     * 仅移除属于当前用户的订单，不报错。
     *
     * @param uid      用户 ID
     * @param orderIds 要删除的订单 ID 列表
     * @return true（始终返回成功）
     */
    public boolean delete(String uid, List<String> orderIds) {
        if (orderIds == null) return true;

        for (String id : orderIds) {
            JSONObject order = orderStore.getById(id);
            if (orderStore.isOrderOwner(order, uid)) {
                orderStore.remove(id);
            }
        }
        orderStore.saveOrders();
        return true;
    }

    // ==================== 模拟发货 ====================

    /**
     * 模拟发货。
     * <p>
     * 规则：仅 orderState === 2（待发货）可发货 → 状态变为 3（待收货）。
     * 已取消订单不能发货。
     *
     * @param orderId 订单 ID
     * @param uid     用户 ID（用于所有权校验）
     * @return 订单对象（成功）或 null（订单不存在/不属于该用户）
     * @throws IllegalStateException 如果订单状态不允许发货
     */
    public JSONObject ship(String orderId, String uid) {
        JSONObject order = orderStore.getById(orderId);
        if (!orderStore.isOrderOwner(order, uid)) {
            return null;
        }

        int state = order.getInt("orderState", 0);
        if (state == 6) {
            throw new IllegalStateException("订单已取消，无法发货");
        }
        if (state != 2) {
            throw new IllegalStateException("当前订单状态不允许发货，仅待发货订单可发货");
        }

        order.set("orderState", 3);
        order.set("deliveryTime", LocalDateTime.now().toString());

        // 模拟物流信息
        JSONObject logistics = new JSONObject();
        logistics.set("company", "顺丰速运");
        logistics.set("number", "SF" + System.currentTimeMillis());
        logistics.set("status", "已发货");
        order.set("logistics", logistics);

        // 持久化
        orderStore.put(orderId, order);
        orderStore.saveOrders();

        return order;
    }

    // ==================== 确认收货 ====================

    /**
     * 确认收货。
     * <p>
     * 规则：仅 orderState === 3（待收货）可确认 → 状态变为 4（待评价）。
     *
     * @param orderId 订单 ID
     * @param uid     用户 ID（用于所有权校验）
     * @return 订单对象（成功）或 null（订单不存在/不属于该用户）
     * @throws IllegalStateException 如果订单状态不允许确认收货
     */
    public JSONObject receipt(String orderId, String uid) {
        JSONObject order = orderStore.getById(orderId);
        if (!orderStore.isOrderOwner(order, uid)) {
            return null;
        }

        int state = order.getInt("orderState", 0);
        if (state != 3) {
            throw new IllegalStateException("当前订单状态不允许确认收货，仅待收货订单可确认");
        }

        order.set("orderState", 4);
        order.set("endTime", LocalDateTime.now().toString());

        // 持久化
        orderStore.put(orderId, order);
        orderStore.saveOrders();

        return order;
    }
}
