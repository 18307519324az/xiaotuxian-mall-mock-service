package com.xtx.mock.service.order;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeOrderStore;
import com.xtx.mock.util.MockStockUtils;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;

/**
 * OrderActionMockService — 订单状态变更业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>取消订单（state 1 → 6）+ 释放锁定库存</li>
 *   <li>删除订单（软/硬删除）</li>
 *   <li>模拟发货（state 2 → 3，含物流信息）</li>
 *   <li>确认收货（state 3 → 4）</li>
 * </ul>
 */
@Slf4j
public class OrderActionMockService {

    private final RuntimeOrderStore orderStore;
    private final StockChangeLogService stockChangeLogService;

    public OrderActionMockService(RuntimeOrderStore orderStore, StockChangeLogService stockChangeLogService) {
        this.orderStore = orderStore;
        this.stockChangeLogService = stockChangeLogService;
    }

    // ==================== 取消订单 ====================

    /**
     * 取消订单。
     * <p>
     * 规则：
     * <ol>
     *   <li>仅 orderState === 1（待付款）可取消 → 状态变为 6（已取消）</li>
     *   <li>已取消订单重复调用直接返回成功（幂等）</li>
     *   <li>释放锁定库存：lockedStock -= count, availableStock += count</li>
     * </ol>
     */
    public JSONObject cancel(String orderId, String uid, String cancelReason) {
        JSONObject order = orderStore.getById(orderId);
        if (order == null) {
            String resolvedOrderId = orderStore.getIdByOrderNo(orderId);
            if (resolvedOrderId != null) {
                orderId = resolvedOrderId;
                order = orderStore.getById(orderId);
            }
        }
        if (!orderStore.isOrderOwner(order, uid)) {
            return null;
        }

        synchronized (order) {
            int state = order.getInt("orderState", 0);
            // 已取消 → 幂等返回
            if (state == 6) {
                return order;
            }
            if (state != 1) {
                throw new IllegalStateException("当前订单状态不允许取消，仅待付款订单可取消");
            }

            // 释放锁定库存：lockedStock -= count, availableStock += count
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
                        int newAvail = beforeAvail + count;

                        if (beforeLocked < count) {
                            log.warn("取消订单 {} SKU {} 锁定库存不足: locked={}, count={}",
                                    orderId, skuItem.getStr("skuId"), beforeLocked, count);
                            // 仍然继续，能释放多少释放多少
                            newAvail = beforeAvail + beforeLocked;
                            newLocked = 0;
                        }

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
                    // 更新商品 SPU 库存
                    JSONObject product = getRuntimeProduct(skuItem.getStr("goodsId"));
                    if (product != null) {
                        int pBeforeAvail = product.getInt("availableStock", product.getInt("inventory", 0));
                        int pBeforeLocked = product.getInt("lockedStock", 0);
                        int pNewLocked = Math.max(0, pBeforeLocked - count);
                        int pNewAvail = pBeforeAvail + count;
                        product.set("availableStock", pNewAvail);
                        product.set("inventory", pNewAvail);
                        product.set("lockedStock", pNewLocked);
                        product.set("stockStatus", MockStockUtils.stockStatus(product));
                    }
                }
            }

            order.set("orderState", 6);
            String reason = (cancelReason != null && !cancelReason.isBlank())
                    ? cancelReason : "用户主动取消";
            order.set("cancelReason", reason);
            String now = LocalDateTime.now().toString();
            order.set("cancelTime", now);
            order.set("closeTime", now);

            orderStore.put(orderId, order);
            orderStore.saveOrders();
        }

        return order;
    }

    // ==================== 删除订单 ====================

    /**
     * 删除订单（从运行时存储中移除）。
     * 仅移除属于当前用户的订单，不报错。
     */
    public boolean delete(String uid, List<String> orderIds) {
        if (orderIds == null) return true;

        for (String id : orderIds) {
            JSONObject order = orderStore.getById(id);
            if (orderStore.isOrderOwner(order, uid)) {
                // 已取消订单删除不需要释放库存（已在取消时释放）
                // 其他状态订单删除：释放锁定库存
                int state = order.getInt("orderState", 0);
                if (state == 1) {
                    // 待付款订单删除前释放锁定库存
                    releaseLockedStockForOrder(order);
                }
                orderStore.remove(id);
            }
        }
        orderStore.saveOrders();
        return true;
    }

    /**
     * 释放订单锁定库存（删除时使用）。
     */
    private void releaseLockedStockForOrder(JSONObject order) {
        JSONArray skus = order.getJSONArray("skus");
        if (skus == null) return;
        for (Object obj : skus) {
            JSONObject skuItem = (JSONObject) obj;
            int count = skuItem.getInt("count", 1);
            JSONObject sku = getRuntimeSku(skuItem.getStr("skuId"));
            if (sku != null) {
                int beforeLocked = MockStockUtils.lockedStock(sku);
                int beforeAvail = MockStockUtils.availableStock(sku);
                int newLocked = Math.max(0, beforeLocked - count);
                int newAvail = beforeAvail + Math.min(count, beforeLocked);
                sku.set("availableStock", newAvail);
                sku.set("inventory", newAvail);
                sku.set("stock", newAvail);
                sku.set("lockedStock", newLocked);
                sku.set("stockStatus", MockStockUtils.stockStatus(sku));
            }
        }
    }

    // ==================== 模拟发货 ====================

    /**
     * 模拟发货。
     * 规则：仅 orderState === 2（待发货）可发货 → 状态变为 3（待收货）。
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

        JSONObject logistics = new JSONObject();
        logistics.set("company", "顺丰速运");
        logistics.set("number", "SF" + System.currentTimeMillis());
        logistics.set("status", "已发货");
        order.set("logistics", logistics);

        orderStore.put(orderId, order);
        orderStore.saveOrders();

        return order;
    }

    // ==================== 确认收货 ====================

    /**
     * 确认收货。
     * 规则：仅 orderState === 3（待收货）可确认 → 状态变为 4（待评价）。
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

        orderStore.put(orderId, order);
        orderStore.saveOrders();

        return order;
    }

    // ==================== SKU / Product 数据访问 ====================

    /**
     * 获取运行时 SKU 数据。
     * 由 Controller 通过 setter 注入。
     */
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
