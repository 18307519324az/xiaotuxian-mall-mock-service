package com.xtx.mock.service.order;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeOrderStore;
import com.xtx.mock.util.MockStockUtils;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 统一处理待付款订单的付款超时状态流转。
 * <p>
 * 超时取消时释放锁定库存：lockedStock -= count, availableStock += count
 */
@Slf4j
public class OrderExpirationMockService {

    public static final String TIMEOUT_CANCEL_REASON = "支付超时自动取消";

    private final RuntimeOrderStore orderStore;
    private final StockChangeLogService stockChangeLogService;

    public OrderExpirationMockService(RuntimeOrderStore orderStore, StockChangeLogService stockChangeLogService) {
        this.orderStore = orderStore;
        this.stockChangeLogService = stockChangeLogService;
    }

    /**
     * 如果待付款订单已经到达付款截止时间，则将其取消并立即持久化。
     * 同时释放锁定库存。
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

            // 释放锁定库存
            releaseLockedStock(order);

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
     * 释放订单中所有 SKU 的锁定库存。
     */
    public void releaseLockedStock(JSONObject order) {
        JSONArray skus = order.getJSONArray("skus");
        if (skus == null) return;

        for (Object obj : skus) {
            JSONObject skuItem = (JSONObject) obj;
            int count = skuItem.getInt("count", 1);
            JSONObject sku = getRuntimeSku(skuItem.getStr("skuId"));
            if (sku != null) {
                int beforeAvail = MockStockUtils.availableStock(sku);
                int beforeLocked = MockStockUtils.lockedStock(sku);
                int beforeSold = MockStockUtils.soldStock(sku);

                int newLocked = Math.max(0, beforeLocked - count);
                int newAvail = beforeAvail + Math.min(count, beforeLocked);

                sku.set("availableStock", newAvail);
                sku.set("inventory", newAvail);
                sku.set("stock", newAvail);
                sku.set("lockedStock", newLocked);
                sku.set("stockStatus", MockStockUtils.stockStatus(sku));

                stockChangeLogService.record(
                        order.getStr("orderNo"), skuItem.getStr("skuId"), skuItem.getStr("goodsId"),
                        StockChangeLogService.CHANGE_TYPE_RELEASE, count,
                        beforeAvail, newAvail,
                        beforeLocked, newLocked,
                        beforeSold, beforeSold);
            }
            JSONObject product = getRuntimeProduct(skuItem.getStr("goodsId"));
            if (product != null) {
                int pBeforeAvail = product.getInt("availableStock", product.getInt("inventory", 0));
                int pBeforeLocked = product.getInt("lockedStock", 0);
                int pNewLocked = Math.max(0, pBeforeLocked - count);
                int pNewAvail = pBeforeAvail + Math.min(count, pBeforeLocked);
                product.set("availableStock", pNewAvail);
                product.set("inventory", pNewAvail);
                product.set("lockedStock", pNewLocked);
                product.set("stockStatus", MockStockUtils.stockStatus(product));
            }
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

    // ==================== SKU / Product 数据访问 ====================

    protected JSONObject getRuntimeSku(String skuId) {
        return runtimeSkus != null ? runtimeSkus.getJSONObject(skuId) : null;
    }

    protected JSONObject getRuntimeProduct(String goodsId) {
        return runtimeProducts != null ? runtimeProducts.getJSONObject(goodsId) : null;
    }

    private JSONObject runtimeSkus;
    private JSONObject runtimeProducts;

    public void setRuntimeSkus(JSONObject skus) {
        this.runtimeSkus = skus;
    }

    public void setRuntimeProducts(JSONObject products) {
        this.runtimeProducts = products;
    }
}
