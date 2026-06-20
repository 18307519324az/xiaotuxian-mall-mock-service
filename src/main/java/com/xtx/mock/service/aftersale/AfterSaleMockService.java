package com.xtx.mock.service.aftersale;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeAfterSaleStore;
import com.xtx.mock.util.MockImageUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AfterSaleMockService — 售后模块业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>查询当前用户售后列表 + 可售后商品候选项</li>
 *   <li>创建售后申请</li>
 *   <li>校验订单归属、SKU 归属</li>
 *   <li>保持旧响应结构不变</li>
 * </ul>
 * <p>
 * 积分操作（读取/写入 runtimePoints）由调用方传入相关引用，
 * 因为积分模块不在 v1.8.5 提取范围内。
 */
@Slf4j
public class AfterSaleMockService {

    private final RuntimeAfterSaleStore store;

    public AfterSaleMockService(RuntimeAfterSaleStore store) {
        this.store = store;
    }

    /**
     * 默认兜底图片。
     */
    private static final String DEFAULT_FALLBACK_IMAGE = "https://picsum.photos/seed/fallback/400/400";

    /**
     * Seed 默认用户的售后数据（保持向后兼容）。
     * 为有订单的用户创建一条默认售后记录（基于该用户第一笔订单的第一个 SKU）。
     * 仅当该用户尚无售后记录时生效。
     *
     * @param userId       用户 ID
     * @param runtimeOrders 所有订单数据（只读）
     * @param featureCounter ID 生成器
     */
    public void seedDefaultAfterSales(String userId,
                                       Map<String, JSONObject> runtimeOrders,
                                       AtomicInteger featureCounter) {
        JSONArray existing = store.getAfterSalesRaw(userId);
        if (existing != null && existing.size() > 0) return;

        for (JSONObject order : runtimeOrders.values()) {
            if (!isOrderOwner(order, userId)) continue;
            JSONArray skus = order.getJSONArray("skus");
            if (skus == null || skus.isEmpty()) continue;
            JSONObject sku = skus.getJSONObject(0);
            JSONObject item = new JSONObject()
                    .set("id", "after_" + featureCounter.incrementAndGet())
                    .set("orderId", order.getStr("id"))
                    .set("skuId", sku.getStr("skuId"))
                    .set("type", "refund")
                    .set("reason", "包装破损")
                    .set("description", "外包装轻微破损，已提交售后登记。")
                    .set("status", "processing")
                    .set("createdAt", "2026-06-09 14:20:00");
            store.addAfterSale(userId, item);
            break;
        }
    }

    /**
     * 判断订单是否属于指定用户。
     */
    private boolean isOrderOwner(JSONObject order, String uid) {
        return uid.equals(order.getStr("userId", ""));
    }

    /**
     * 查询当前用户的售后列表和可售后商品候选项。
     *
     * @param uid           当前用户 ID
     * @param orderId       可选的订单 ID 过滤（如果传了，只返回该订单的可售后商品）
     * @param runtimeOrders 所有订单数据（只读）
     * @return JSONObject 包含 items, candidates, serviceTypes, reasons
     */
    public JSONObject queryAfterSales(String uid, String orderId,
                                       Map<String, JSONObject> runtimeOrders) {
        JSONArray candidates = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        for (JSONObject order : runtimeOrders.values()) {
            if (!isOrderOwner(order, uid)) continue;
            if (orderId != null && !orderId.isBlank() && !orderId.equals(order.getStr("id"))) continue;
            JSONArray skus = order.getJSONArray("skus");
            if (skus == null) continue;
            for (Object obj : skus) {
                JSONObject sku = (JSONObject) obj;
                String key = order.getStr("id") + ":" + sku.getStr("skuId");
                if (!seen.add(key)) continue;
                String image = sku.getStr("image", "");
                if (image == null || image.isBlank()) {
                    image = sku.getStr("picture", "");
                }
                Object quantity = sku.get("quantity");
                if (quantity == null) quantity = sku.get("count");
                candidates.add(new JSONObject()
                        .set("orderId", order.getStr("id"))
                        .set("skuId", sku.getStr("skuId"))
                        .set("spuId", sku.getStr("spuId", ""))
                        .set("name", sku.getStr("name"))
                        .set("attrsText", sku.getStr("attrsText", ""))
                        .set("image", MockImageUtils.normalizeImageUrl(image, DEFAULT_FALLBACK_IMAGE))
                        .set("quantity", quantity != null ? quantity : 1)
                        .set("realPay", sku.get("realPay")));
            }
        }
        JSONObject result = new JSONObject();
        result.set("items", store.getAfterSales(uid));
        result.set("candidates", candidates);
        JSONArray serviceTypes = new JSONArray();
        serviceTypes.add("refund");
        serviceTypes.add("exchange");
        serviceTypes.add("repair");
        JSONArray reasons = new JSONArray();
        reasons.add("包装破损");
        reasons.add("商品瑕疵");
        reasons.add("尺码不合适");
        reasons.add("其他");
        result.set("serviceTypes", serviceTypes);
        result.set("reasons", reasons);
        return result;
    }

    /**
     * 提交售后申请。
     *
     * @param uid           当前用户 ID
     * @param orderId       订单 ID
     * @param skuId         SKU ID
     * @param type          售后类型（refund/exchange/repair）
     * @param reason        售后原因
     * @param description   售后说明
     * @param runtimeOrders 所有订单数据（只读）
     * @param featureCounter ID 生成器
     * @return JSONObject 售后申请记录，或 null 表示校验失败（错误信息通过抛异常或返回错误）
     * @throws IllegalArgumentException 如果校验失败，包含错误信息
     */
    public JSONObject submitAfterSale(String uid, String orderId, String skuId,
                                       String type, String reason, String description,
                                       Map<String, JSONObject> runtimeOrders,
                                       AtomicInteger featureCounter) {
        // 校验订单是否存在
        JSONObject order = runtimeOrders.get(orderId);
        if (order == null || !isOrderOwner(order, uid)) {
            throw new IllegalArgumentException("订单不存在");
        }

        // 校验 SKU 是否属于订单
        JSONArray orderSkus = order.getJSONArray("skus");
        boolean skuBelongsToOrder = orderSkus != null && orderSkus.stream()
                .map(item -> (JSONObject) item)
                .anyMatch(item -> skuId.equals(item.getStr("skuId")));
        if (!skuBelongsToOrder) {
            throw new IllegalArgumentException("售后商品不属于当前订单");
        }

        JSONObject item = new JSONObject()
                .set("id", "after_" + featureCounter.incrementAndGet())
                .set("orderId", orderId)
                .set("skuId", skuId)
                .set("type", type)
                .set("reason", reason)
                .set("description", description)
                .set("status", "submitted")
                .set("createdAt", java.time.LocalDateTime.now().toString().replace('T', ' ').substring(0, 19));

        store.addAfterSale(uid, item);
        log.info("After-sale submitted: user={} order={} sku={} type={}", uid, orderId, skuId, type);
        return item;
    }
}
