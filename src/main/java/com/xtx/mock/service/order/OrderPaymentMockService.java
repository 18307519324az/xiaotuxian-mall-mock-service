package com.xtx.mock.service.order;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeOrderStore;
import com.xtx.mock.util.MockStockUtils;
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
 *   <li>确认扣减库存：lockedStock -= count, soldStock += count</li>
 *   <li>写入 payTime / payChannel</li>
 *   <li>通过 RuntimeOrderStore 持久化</li>
 * </ul>
 */
@Slf4j
public class OrderPaymentMockService {

    private final RuntimeOrderStore orderStore;
    private final OrderExpirationMockService expirationService;
    private final StockChangeLogService stockChangeLogService;

    public OrderPaymentMockService(RuntimeOrderStore orderStore, StockChangeLogService stockChangeLogService) {
        this.orderStore = orderStore;
        this.expirationService = new OrderExpirationMockService(orderStore, stockChangeLogService);
        this.stockChangeLogService = stockChangeLogService;
    }

    // ==================== 支付 ====================

    /**
     * 支付订单。
     * <p>
     * 规则：
     * <ol>
     *   <li>校验订单状态是否允许支付</li>
     *   <li>已支付订单直接返回成功（幂等）</li>
     *   <li>已取消订单拒绝支付</li>
     *   <li>支付后锁定库存转为已售库存：lockedStock -= count, soldStock += count</li>
     *   <li>订单状态流转：1(待付款) → 2(待发货)</li>
     * </ol>
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
            // 已支付状态：待发货(2)、待收货(3)、待评价(4)、已完成(5) → 幂等返回
            if (state == 2 || state == 3 || state == 4 || state == 5) {
                return buildPayResult(order, orderId);
            }
            if (state == 6) {
                throw new IllegalStateException("订单已取消，无法支付");
            }
            if (state != 1) {
                throw new IllegalStateException("当前订单状态不允许支付");
            }

            // 确认扣减库存：lockedStock -= count, soldStock += count
            JSONArray skus = order.getJSONArray("skus");
            if (skus != null && !skus.isEmpty()) {
                for (Object obj : skus) {
                    JSONObject skuItem = (JSONObject) obj;
                    int count = skuItem.getInt("count", 1);
                    JSONObject sku = getRuntimeSku(skuItem.getStr("skuId"));
                    if (sku != null) {
                        int beforeAvail = MockStockUtils.availableStock(sku);
                        int beforeLocked = MockStockUtils.lockedStock(sku);
                        int beforeSold = MockStockUtils.soldStock(sku);

                        int newLocked = Math.max(0, beforeLocked - count);
                        int newSold = beforeSold + count;

                        if (beforeLocked < count) {
                            throw new IllegalStateException("锁定库存不足，无法支付: " + skuItem.getStr("skuId"));
                        }

                        sku.set("lockedStock", newLocked);
                        sku.set("soldStock", newSold);
                        sku.set("stockStatus", MockStockUtils.stockStatus(sku));

                        stockChangeLogService.record(
                                order.getStr("orderNo"), skuItem.getStr("skuId"), skuItem.getStr("goodsId"),
                                StockChangeLogService.CHANGE_TYPE_CONFIRM, -count,
                                beforeAvail, beforeAvail,
                                beforeLocked, newLocked,
                                beforeSold, newSold);
                    }
                    // 更新商品 SPU 库存
                    JSONObject product = getRuntimeProduct(skuItem.getStr("goodsId"));
                    if (product != null) {
                        int pBeforeLocked = product.getInt("lockedStock", 0);
                        int pBeforeSold = product.getInt("soldStock", 0);
                        int pNewLocked = Math.max(0, pBeforeLocked - count);
                        int pNewSold = pBeforeSold + count;
                        product.set("lockedStock", pNewLocked);
                        product.set("soldStock", pNewSold);
                        product.set("stockStatus", MockStockUtils.stockStatus(product));
                    }
                }
            }

            order.set("orderState", 2);
            order.set("payTime", now.toString());
            if (payChannel != null) {
                order.set("payChannel", payChannel);
            }

            orderStore.put(orderId, order);
            orderStore.saveOrders();
        }

        return buildPayResult(order, orderId);
    }

    public JSONObject payByOrderNo(String orderNo, String uid, Object payChannel) {
        String orderId = orderStore.getIdByOrderNo(orderNo);
        if (orderId == null) {
            return null;
        }
        return pay(orderId, uid, payChannel);
    }

    private JSONObject buildPayResult(JSONObject order, String orderId) {
        JSONObject result = new JSONObject();
        result.set("id", orderId);
        result.set("orderState", order.getInt("orderState", 2));
        result.set("payChannel", order.get("payChannel"));
        result.set("payTime", order.get("payTime"));
        return result;
    }

    /**
     * 获取运行时 SKU 数据（masterSkus 引用在运行时被修改）。
     * 子类可覆盖此方法以提供不同的数据源。
     */
    protected JSONObject getRuntimeSku(String skuId) {
        // 由 MockOrderPaymentController 通过 setter 注入
        return runtimeSkus != null ? runtimeSkus.getJSONObject(skuId) : null;
    }

    /**
     * 获取运行时商品 SPU 数据。
     */
    protected JSONObject getRuntimeProduct(String goodsId) {
        return runtimeProducts != null ? runtimeProducts.getJSONObject(goodsId) : null;
    }

    /** runtime SKU 引用（由 Controller setter 注入） */
    private JSONObject runtimeSkus;

    /** runtime 商品引用（由 Controller setter 注入） */
    private JSONObject runtimeProducts;

    public void setRuntimeSkus(JSONObject skus) {
        this.runtimeSkus = skus;
    }

    public void setRuntimeProducts(JSONObject products) {
        this.runtimeProducts = products;
    }
}
