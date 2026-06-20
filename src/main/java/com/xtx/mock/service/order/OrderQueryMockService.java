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
 */
@Slf4j
public class OrderQueryMockService {

    private final RuntimeOrderStore orderStore;
    private final JSONObject masterProducts;
    private final BiFunction<String, String, String> imageNormalizer;
    private final String defaultFallbackImage;
    private final Object logisticsData;
    private final OrderExpirationMockService expirationService;
    private final JSONObject masterSkus;

    public OrderQueryMockService(RuntimeOrderStore orderStore,
                                  JSONObject masterProducts,
                                  BiFunction<String, String, String> imageNormalizer,
                                  String defaultFallbackImage,
                                  Object logisticsData,
                                  JSONObject masterSkus,
                                  StockChangeLogService stockChangeLogService) {
        this.orderStore = orderStore;
        this.masterProducts = masterProducts;
        this.imageNormalizer = imageNormalizer;
        this.defaultFallbackImage = defaultFallbackImage;
        this.logisticsData = logisticsData;
        this.masterSkus = masterSkus;
        this.expirationService = new OrderExpirationMockService(orderStore, stockChangeLogService);
        this.expirationService.setRuntimeSkus(masterSkus);
        this.expirationService.setRuntimeProducts(masterProducts);
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
            if (!uid.equals(order.getStr("userId", ""))) continue;
            if (orderState > 0 && order.getInt("orderState", 0) != orderState) continue;
            allOrders.add(order);
        }

        // 按创建时间降序
        allOrders.sort((a, b) -> {
            String ta = a.getStr("createTime", "");
            String tb = b.getStr("createTime", "");
            return tb.compareTo(ta);
        });

        // 分页
        int total = allOrders.size();
        int from = Math.max(0, (page - 1) * pageSize);
        int to = Math.min(total, from + pageSize);

        JSONArray items = new JSONArray();
        for (int i = from; i < to; i++) {
            items.add(buildOrderVO(allOrders.get(i)));
        }

        JSONObject result = new JSONObject();
        result.set("counts", total);
        result.set("page", page);
        result.set("pageSize", pageSize);
        result.set("pages", Math.max(1, (int) Math.ceil(total / (double) pageSize)));
        result.set("items", items);
        return result;
    }

    // ==================== 订单详情 ====================

    /**
     * 查询订单详情。
     */
    public JSONObject detail(String orderId, String uid) {
        JSONObject order = orderStore.getById(orderId);
        if (!orderStore.isOrderOwner(order, uid)) {
            return null;
        }
        expirationService.expireUnpaidOrderIfNeeded(order, LocalDateTime.now());
        return buildOrderVO(order);
    }

    // ==================== 物流信息 ====================

    /**
     * 查询物流信息。
     */
    public Object logistics(String orderId, String uid) {
        JSONObject order = orderStore.getById(orderId);
        if (!orderStore.isOrderOwner(order, uid)) {
            return null;
        }
        JSONObject logistics = order.getJSONObject("logistics");
        return logistics != null ? logistics : logisticsData;
    }

    // ==================== 后台扫描过期订单 ====================

    /**
     * 扫描并取消所有已过期的待付款订单（由 @Scheduled 调用）。
     */
    public int expireAllPendingOrders() {
        return expirationService.expireAllPendingOrders(LocalDateTime.now());
    }

    // ==================== VO 构建 ====================

    /**
     * 构建订单展示 VO。
     */
    private JSONObject buildOrderVO(JSONObject order) {
        // 倒计时
        int countdown = computeCountdown(order);
        order.set("countdown", countdown);

        // 更新 SKU 的现价和库存（从 master 数据获取）
        JSONArray skus = order.getJSONArray("skus");
        if (skus != null) {
            for (Object obj : skus) {
                JSONObject skuItem = (JSONObject) obj;
                String skuId = skuItem.getStr("skuId");
                if (skuId != null && masterSkus != null) {
                    JSONObject sku = masterSkus.getJSONObject(skuId);
                    if (sku != null) {
                        skuItem.set("availableStock", sku.getInt("availableStock", skuItem.getInt("availableStock", 0)));
                        skuItem.set("stockStatus", sku.getStr("stockStatus", skuItem.getStr("stockStatus", "IN_STOCK")));
                    }
                }
            }
        }

        return order;
    }

    /**
     * 计算订单倒计时（秒）。
     */
    private int computeCountdown(JSONObject order) {
        int state = order.getInt("orderState", 0);
        if (state != 1) {
            return -1;
        }
        String payExpireTime = order.getStr("payExpireTime");
        if (payExpireTime == null || payExpireTime.isBlank()) {
            return -1;
        }
        try {
            LocalDateTime expire = LocalDateTime.parse(payExpireTime);
            LocalDateTime now = LocalDateTime.now();
            if (now.isAfter(expire)) {
                return 0;
            }
            long seconds = Duration.between(now, expire).getSeconds();
            return (int) Math.max(0, seconds);
        } catch (Exception e) {
            return -1;
        }
    }
}
