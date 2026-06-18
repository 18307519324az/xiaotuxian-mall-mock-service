package com.xtx.mock.service.order;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeOrderStore;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BiFunction;

/**
 * OrderQueryMockService — 订单查询业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>订单列表查询（分页、按 userId 隔离、自动取消过期订单）</li>
 *   <li>订单详情查询（含 SKU 字段补齐、倒计时计算）</li>
 *   <li>物流信息查询</li>
 * </ul>
 * <p>
 * 不修改订单状态（自动取消过期订单除外，该行为是 GET /member/order 的既有契约）。
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockOrderQueryController} 直接实例化。
 */
@Slf4j
public class OrderQueryMockService {

    private final RuntimeOrderStore orderStore;
    private final JSONObject masterProducts;
    private final BiFunction<String, String, String> imageNormalizer;
    private final String defaultFallbackImage;
    private final Object logisticsData;
    private final OrderExpirationMockService expirationService;

    public OrderQueryMockService(RuntimeOrderStore orderStore,
                                  JSONObject masterProducts,
                                  BiFunction<String, String, String> imageNormalizer,
                                  String defaultFallbackImage,
                                  Object logisticsData) {
        this.orderStore = orderStore;
        this.masterProducts = masterProducts;
        this.imageNormalizer = imageNormalizer;
        this.defaultFallbackImage = defaultFallbackImage;
        this.logisticsData = logisticsData;
        this.expirationService = new OrderExpirationMockService(orderStore);
    }

    // ==================== 订单列表 ====================

    /**
     * 查询订单列表（分页）。
     * 自动过期待付款订单，按创建时间降序排列。
     */
    public JSONObject list(String uid, int page, int pageSize, int orderState) {
        expirationService.expireAllPendingOrders(LocalDateTime.now());

        // 收集当前用户订单
        List<JSONObject> allOrders = new ArrayList<>();
        for (Map.Entry<String, JSONObject> entry : orderStore.entrySet()) {
            JSONObject order = entry.getValue();
            if (!orderStore.isOrderOwner(order, uid)) continue;
            if (orderState > 0 && orderState != order.getInt("orderState", 0)) {
                continue;
            }
            allOrders.add(order);
        }

        // 按创建时间降序排列
        allOrders.sort((a, b) -> b.getStr("createTime", "").compareTo(a.getStr("createTime", "")));

        int total = allOrders.size();
        int pages = (int) Math.ceil((double) total / pageSize);
        int fromIndex = (page - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, total);

        JSONArray pageItems = new JSONArray();
        if (fromIndex < total) {
            for (JSONObject order : allOrders.subList(fromIndex, toIndex)) {
                pageItems.add(buildOrderListItem(order));
            }
        }

        JSONObject result = new JSONObject();
        result.set("counts", total);
        result.set("pageSize", pageSize);
        result.set("pages", pages);
        result.set("page", page);
        result.set("items", pageItems);

        return result;
    }

    // ==================== 订单详情 ====================

    /**
     * 查询订单详情。
     *
     * @return 订单详情 JSONObject，如果不存在或不属于该用户返回 null
     */
    public JSONObject detail(String orderId, String uid) {
        JSONObject order = orderStore.getById(orderId);
        if (!orderStore.isOrderOwner(order, uid)) {
            return null;
        }
        expirationService.expireUnpaidOrderIfNeeded(order, LocalDateTime.now());

        JSONObject result = new JSONObject();
        result.set("id", order.getStr("id"));
        result.set("orderNo", order.getStr("orderNo"));
        result.set("userId", order.getStr("userId"));
        result.set("orderState", order.getInt("orderState", 0));
        result.set("totalMoney", order.getStr("totalMoney"));
        result.set("totalNum", order.getInt("totalNum", 0));
        result.set("payMoney", order.getStr("payMoney"));
        result.set("postFee", order.getStr("postFee"));
        result.set("payChannel", order.getInt("payChannel", 1));
        result.set("payType", order.getInt("payType", 1));
        result.set("createTime", order.getStr("createTime"));
        result.set("payLatestTime", order.getStr("payLatestTime"));
        result.set("payTime", order.getStr("payTime"));
        result.set("consignTime", order.getStr("consignTime"));
        result.set("endTime", order.getStr("endTime"));
        result.set("evaluationTime", order.getStr("evaluationTime"));
        result.set("countdown", calculateDynamicCountdown(order));

        // 取消原因和时间
        String cancelReason = order.getStr("cancelReason");
        if (cancelReason != null && !cancelReason.isBlank()) {
            result.set("cancelReason", cancelReason);
            result.set("cancelTime", order.getStr("cancelTime"));
            result.set("closeTime", order.getStr("closeTime"));
        }

        // 礼品卡抵扣信息
        String gcDetailAmount = order.getStr("giftCardAmount");
        if (gcDetailAmount != null && !gcDetailAmount.isBlank()) {
            result.set("giftCardAmount", gcDetailAmount);
            result.set("giftCardCode", order.getStr("giftCardCode"));
        }

        // 优惠券信息
        String detailCouponId = order.getStr("couponId");
        if (detailCouponId != null && !detailCouponId.isBlank()) {
            result.set("couponId", detailCouponId);
            result.set("couponName", order.getStr("couponName"));
            result.set("couponType", order.getStr("couponType"));
            result.set("discountGoodsAmount", order.getStr("discountGoodsAmount"));
            result.set("discountFreightAmount", order.getStr("discountFreightAmount"));
            result.set("discountAmount", order.getStr("discountAmount"));
        }

        // SKU 列表
        result.set("skus", buildSkuList(order, true));

        // 收货地址快照
        JSONObject receiverAddr = order.getJSONObject("receiverAddress");
        if (receiverAddr == null) {
            receiverAddr = new JSONObject();
        }
        result.set("receiverAddress", receiverAddr);

        return result;
    }

    // ==================== 物流查询 ====================

    /**
     * 查询物流信息。
     *
     * @return 物流数据对象，如果订单不存在或不属于该用户返回 null
     */
    public Object logistics(String orderId, String uid) {
        JSONObject order = orderStore.getById(orderId);
        if (!orderStore.isOrderOwner(order, uid)) {
            return null;
        }
        return logisticsData;
    }

    // ==================== 内部方法 ====================

    /**
     * 构建订单列表项。
     */
    private JSONObject buildOrderListItem(JSONObject order) {
        JSONObject item = new JSONObject();
        item.set("id", order.getStr("id"));
        item.set("orderNo", order.getStr("orderNo"));
        item.set("userId", order.getStr("userId"));
        item.set("orderState", order.getInt("orderState", 0));
        item.set("totalMoney", order.getStr("totalMoney"));
        item.set("totalNum", order.getInt("totalNum", 0));
        item.set("payMoney", order.getStr("payMoney"));
        item.set("postFee", order.getStr("postFee"));
        item.set("createTime", order.getStr("createTime"));
        item.set("countdown", calculateDynamicCountdown(order));

        // 取消原因和时间
        String cancelReason = order.getStr("cancelReason");
        if (cancelReason != null && !cancelReason.isBlank()) {
            item.set("cancelReason", cancelReason);
            item.set("cancelTime", order.getStr("cancelTime"));
        }

        // 优惠券抵扣信息
        String discountAmount = order.getStr("discountAmount");
        if (discountAmount != null && !discountAmount.isBlank()) {
            item.set("discountAmount", discountAmount);
            item.set("couponId", order.getStr("couponId"));
            item.set("couponName", order.getStr("couponName"));
            item.set("couponType", order.getStr("couponType"));
            item.set("discountGoodsAmount", order.getStr("discountGoodsAmount"));
            item.set("discountFreightAmount", order.getStr("discountFreightAmount"));
        }

        // 礼品卡抵扣信息
        String gcAmount = order.getStr("giftCardAmount");
        if (gcAmount != null && !gcAmount.isBlank()) {
            item.set("giftCardAmount", gcAmount);
            item.set("giftCardCode", order.getStr("giftCardCode"));
        }

        // SKU 列表
        item.set("skus", buildSkuList(order, false));

        return item;
    }

    /**
     * 构建 SKU 列表（含字段别名映射和 masterProducts 补齐）。
     *
     * @param order      订单对象
     * @param detailMode 是否为详情模式（详情模式含 curPrice 字段）
     */
    private JSONArray buildSkuList(JSONObject order, boolean detailMode) {
        JSONArray orderSkus = order.getJSONArray("skus");
        JSONArray skus = new JSONArray();
        if (orderSkus == null) return skus;

        for (Object obj : orderSkus) {
            JSONObject s = (JSONObject) obj;
            JSONObject sku = new JSONObject();
            sku.set("id", s.getStr("id"));
            sku.set("skuId", s.getStr("skuId"));
            sku.set("spuId", s.getStr("spuId", s.getStr("goodsId")));
            sku.set("name", s.getStr("name"));
            sku.set("attrsText", s.getStr("attrsText"));

            // 图片别名映射: picture → image
            String pic = s.getStr("image");
            if (pic == null || pic.isBlank()) {
                pic = s.getStr("picture", defaultFallbackImage);
            }
            sku.set("image", imageNormalizer.apply(pic, defaultFallbackImage));
            sku.set("picture", pic);

            sku.set("price", s.getStr("price"));
            sku.set("nowPrice", s.getStr("nowPrice"));

            if (detailMode) {
                sku.set("curPrice", s.getStr("curPrice", s.getStr("nowPrice", "0.00")));
            }

            // 数量别名: count → quantity
            Object countObj = s.get("quantity");
            if (countObj == null) countObj = s.get("count");
            sku.set("quantity", countObj != null ? countObj : 1);
            sku.set("count", countObj != null ? countObj : 1);

            // 实付金额别名: totalPayMoney → realPay
            String realPay = s.getStr("realPay");
            if (realPay == null || realPay.isBlank()) {
                realPay = s.getStr("totalPayMoney", "0.00");
            }
            sku.set("realPay", realPay);
            sku.set("totalPayMoney", realPay);

            // 从 masterProducts 补齐 name/picture/price/desc
            fallbackSkuFromMaster(sku, s);

            skus.add(sku);
        }
        return skus;
    }

    /**
     * 从 masterProducts 补齐 SKU 字段。
     */
    private void fallbackSkuFromMaster(JSONObject sku, JSONObject sourceSku) {
        String skuName = sku.getStr("name");
        if (skuName == null) skuName = "";
        String skuPic = sku.getStr("picture");
        if (skuPic == null) skuPic = "";
        String skuPrice = sku.getStr("price");
        if (skuPrice == null) skuPrice = "";
        boolean needsFallback = skuName.isEmpty() || skuPic.isEmpty() || skuPrice.isEmpty();

        if (!needsFallback) return;
        if (masterProducts == null) return;

        // 优先 productId，其次 goodsId，最后用 skuId 反查
        String productId = sourceSku.getStr("productId");
        if (productId == null) productId = "";
        String goodsId = sourceSku.getStr("goodsId");
        if (goodsId == null) goodsId = "";
        String skuId = sourceSku.getStr("skuId");
        if (skuId == null) skuId = "";
        String lookupKey = productId;
        if (lookupKey.isEmpty()) lookupKey = goodsId;
        if (lookupKey.isEmpty() && !skuId.isEmpty()) {
            for (String pid : masterProducts.keySet()) {
                Object pv = masterProducts.get(pid);
                if (!(pv instanceof JSONObject)) continue;
                JSONObject prod = (JSONObject) pv;
                Object sv = prod.get("skus");
                if (!(sv instanceof JSONArray)) continue;
                JSONArray prodSkus = (JSONArray) sv;
                for (int si = 0; si < prodSkus.size(); si++) {
                    Object psObj = prodSkus.get(si);
                    if (!(psObj instanceof JSONObject)) continue;
                    JSONObject ps = (JSONObject) psObj;
                    String psId = ps.getStr("id");
                    if (skuId.equals(psId)) {
                        lookupKey = pid;
                        break;
                    }
                }
                if (!lookupKey.isEmpty()) break;
            }
        }
        if (lookupKey.isEmpty()) return;

        Object pv = masterProducts.get(lookupKey);
        if (!(pv instanceof JSONObject)) return;
        JSONObject product = (JSONObject) pv;

        if (skuName.isEmpty()) {
            String pn = product.getStr("name");
            sku.set("name", pn != null ? pn : "商品已下架");
        }
        if (skuPic.isEmpty()) {
            String prodPic = product.getStr("picture");
            if (prodPic == null) prodPic = "";
            sku.set("picture", prodPic);
            sku.set("image", imageNormalizer.apply(prodPic, defaultFallbackImage));
        }
        if (skuPrice.isEmpty()) {
            String pp = product.getStr("price");
            sku.set("price", pp != null ? pp : "0.00");
        }
        if (sku.getStr("desc") == null || sku.getStr("desc").isEmpty()) {
            String pd = product.getStr("desc");
            sku.set("desc", pd != null ? pd : "");
        }
    }

    /**
     * 计算动态倒计时。
     * 基于 payExpireTime - now 实时计算，不受页面刷新影响。
     * 已过期返回 -1 并自动取消订单。
     */
    private int calculateDynamicCountdown(JSONObject order) {
        if (order == null) return -1;
        int state = order.getInt("orderState", 0);
        if (state != 1) return -1;

        String payExpireTime = order.getStr("payExpireTime");
        if (payExpireTime == null || payExpireTime.isBlank()) {
            String future = LocalDateTime.now().plusMinutes(15).toString();
            order.set("payExpireTime", future);
            order.set("payLatestTime", future);
            payExpireTime = future;
        }

        try {
            LocalDateTime expireTime = LocalDateTime.parse(payExpireTime);
            LocalDateTime now = LocalDateTime.now();
            if (now.isAfter(expireTime) || now.isEqual(expireTime)) {
                expirationService.expireUnpaidOrderIfNeeded(order, now);
                return -1;
            }
            long seconds = Duration.between(now, expireTime).getSeconds();
            return seconds > 0 ? (int) seconds : -1;
        } catch (Exception e) {
            return 0;
        }
    }

    public int expireAllPendingOrders() {
        return expirationService.expireAllPendingOrders(LocalDateTime.now());
    }
}
