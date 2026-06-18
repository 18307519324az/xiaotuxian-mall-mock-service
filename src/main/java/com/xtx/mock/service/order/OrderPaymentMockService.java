package com.xtx.mock.service.order;

import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeOrderStore;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

/**
 * OrderPaymentMockService — 订单支付业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>校验订单属于当前 userId</li>
 *   <li>校验订单状态是否允许支付（仅 state=1 可支付）</li>
 *   <li>处理模拟支付状态流转（state 1 → 2）</li>
 *   <li>写入 payTime / payChannel</li>
 *   <li>通过 RuntimeOrderStore 持久化</li>
 * </ul>
 * <p>
 * 不创建订单，不处理购物车清理，不重新计算订单金额。
 * 不依赖 Spring，由 MockOrderPaymentController 直接实例化。
 */
@Slf4j
public class OrderPaymentMockService {

    private final RuntimeOrderStore orderStore;
    private final OrderExpirationMockService expirationService;

    public OrderPaymentMockService(RuntimeOrderStore orderStore) {
        this.orderStore = orderStore;
        this.expirationService = new OrderExpirationMockService(orderStore);
    }

    // ==================== 支付 ====================

    /**
     * 支付订单。
     * <p>
     * 规则：仅 orderState === 1（待付款）可支付 → 状态变为 2（待发货）。
     *
     * @param orderId    订单 ID
     * @param uid        用户 ID（用于所有权校验）
     * @param payChannel 支付渠道（可选，不传则保持原值）
     * @return 支付结果对象 {id, orderState, payChannel, payTime}（成功）
     *         或 null（订单不存在/不属于该用户）
     * @throws IllegalStateException 如果订单状态不允许支付
     */
    public JSONObject pay(String orderId, String uid, Object payChannel) {
        JSONObject order = orderStore.getById(orderId);
        if (!orderStore.isOrderOwner(order, uid)) {
            return null;
        }

        synchronized (order) {
            LocalDateTime now = LocalDateTime.now();
            if (expirationService.expireUnpaidOrderIfNeeded(order, now)) {
                throw new IllegalStateException("订单支付已超时，已自动取消");
            }

            int state = order.getInt("orderState", 0);
            // 已支付状态：待发货(2)、待收货(3)、待评价(4)、已完成(5)
            if (state == 2 || state == 3 || state == 4 || state == 5) {
                throw new IllegalStateException("订单已支付，请勿重复支付");
            }
            if (state == 6) {
                throw new IllegalStateException("订单已取消，无法支付");
            }
            if (state != 1) {
                throw new IllegalStateException("当前订单状态不允许支付");
            }

            order.set("orderState", 2);
            order.set("payTime", now.toString());
            if (payChannel != null) {
                order.set("payChannel", payChannel);
            }

            orderStore.put(orderId, order);
            orderStore.saveOrders();
        }

        // 构建响应
        JSONObject result = new JSONObject();
        result.set("id", orderId);
        result.set("orderState", 2);
        result.set("payChannel", order.get("payChannel"));
        result.set("payTime", order.get("payTime"));
        return result;
    }
}
