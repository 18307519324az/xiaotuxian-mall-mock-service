package com.xtx.mock.service.order;

import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeOrderStore;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 统一处理待付款订单的付款超时状态流转。
 */
@Slf4j
public class OrderExpirationMockService {

    public static final String TIMEOUT_CANCEL_REASON = "支付超时自动取消";

    private final RuntimeOrderStore orderStore;

    public OrderExpirationMockService(RuntimeOrderStore orderStore) {
        this.orderStore = orderStore;
    }

    /**
     * 如果待付款订单已经到达付款截止时间，则将其取消并立即持久化。
     *
     * @return 订单是否在本次调用中由待付款变为已取消
     */
    public boolean expireUnpaidOrderIfNeeded(JSONObject order, LocalDateTime now) {
        if (order == null || now == null) {
            return false;
        }

        synchronized (order) {
            if (order.getInt("orderState", 0) != 1) {
                return false;
            }

            String payExpireTime = order.getStr("payExpireTime");
            if (payExpireTime == null || payExpireTime.isBlank()) {
                return false;
            }

            final LocalDateTime expireTime;
            try {
                expireTime = LocalDateTime.parse(payExpireTime);
            } catch (DateTimeParseException e) {
                log.warn("订单 {} 的 payExpireTime 无法解析: {}",
                        order.getStr("id"), payExpireTime);
                return false;
            }

            if (now.isBefore(expireTime)) {
                return false;
            }

            String cancelledAt = now.toString();
            order.set("orderState", 6);
            order.set("cancelReason", TIMEOUT_CANCEL_REASON);
            order.set("cancelTime", cancelledAt);
            order.set("closeTime", cancelledAt);
            order.set("countdown", -1);
            orderStore.saveOrders();
            return true;
        }
    }

    /**
     * 扫描并持久化所有已经到达付款截止时间的待付款订单。
     */
    public int expireAllPendingOrders(LocalDateTime now) {
        int expiredCount = 0;
        for (Map.Entry<String, JSONObject> entry : orderStore.entrySet()) {
            if (expireUnpaidOrderIfNeeded(entry.getValue(), now)) {
                expiredCount++;
            }
        }
        return expiredCount;
    }
}
