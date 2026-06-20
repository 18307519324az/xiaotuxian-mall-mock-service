package com.xtx.mock.util;

import cn.hutool.json.JSONObject;

/**
 * Mock 库存字段统一计算工具。
 * <p>
 * 库存三态：availableStock（可用）、lockedStock（锁定）、soldStock（已售）。
 * 约束：totalStock = availableStock + lockedStock + soldStock
 */
public final class MockStockUtils {

    private MockStockUtils() {
    }

    public static int availableStock(JSONObject data) {
        if (data == null) {
            return 0;
        }
        if (data.containsKey("availableStock")) {
            return Math.max(0, data.getInt("availableStock", 0));
        }
        if (data.containsKey("inventory")) {
            return Math.max(0, data.getInt("inventory", 0));
        }
        if (data.containsKey("stock")) {
            return Math.max(0, data.getInt("stock", 0));
        }
        return 0;
    }

    public static int lockedStock(JSONObject data) {
        if (data == null) {
            return 0;
        }
        return Math.max(0, data.getInt("lockedStock", 0));
    }

    public static int soldStock(JSONObject data) {
        if (data == null) {
            return 0;
        }
        return Math.max(0, data.getInt("soldStock", 0));
    }

    /**
     * 计算总库存 = availableStock + lockedStock + soldStock。
     * 若 data 不包含 lockedStock/soldStock 字段，则回退为 availableStock 值（兼容旧数据）。
     */
    public static int totalStock(JSONObject data) {
        if (data == null) {
            return 0;
        }
        if (data.containsKey("lockedStock") || data.containsKey("soldStock")) {
            return availableStock(data) + lockedStock(data) + soldStock(data);
        }
        // 旧数据：无 lockedStock/soldStock，totalStock = availableStock
        return availableStock(data);
    }

    public static int lowStockThreshold(JSONObject data) {
        if (data == null) {
            return 5;
        }
        return Math.max(1, data.getInt("lowStockThreshold", 5));
    }

    public static String stockStatus(JSONObject data) {
        int availableStock = availableStock(data);
        int lowStockThreshold = lowStockThreshold(data);
        if (availableStock <= 0) {
            return "OUT_OF_STOCK";
        }
        if (availableStock <= lowStockThreshold) {
            return "LOW_STOCK";
        }
        return "IN_STOCK";
    }

    /**
     * 应用库存派生字段到 data 对象。
     * 设置 availableStock、stock、inventory、lockedStock、soldStock、lowStockThreshold、stockStatus。
     */
    public static void applyDerivedStockFields(JSONObject data) {
        if (data == null) {
            return;
        }
        int avail = availableStock(data);
        int locked = lockedStock(data);
        int sold = soldStock(data);
        int lowThreshold = lowStockThreshold(data);

        data.set("availableStock", avail);
        data.set("stock", avail);
        data.set("inventory", avail);
        if (!data.containsKey("lockedStock")) {
            data.set("lockedStock", locked);
        }
        if (!data.containsKey("soldStock")) {
            data.set("soldStock", sold);
        }
        data.set("lowStockThreshold", lowThreshold);
        data.set("stockStatus", stockStatus(data));
    }

    /**
     * 校验库存一致性：
     * 1. totalStock = availableStock + lockedStock + soldStock
     * 2. availableStock >= 0
     * 3. lockedStock >= 0
     * 4. soldStock >= 0
     * 5. stockStatus 与 availableStock 匹配
     */
    public static String checkConsistency(JSONObject data, String itemId) {
        if (data == null) return "数据为空";

        int avail = data.getInt("availableStock", 0);
        int locked = data.getInt("lockedStock", 0);
        int sold = data.getInt("soldStock", 0);
        // total 从原始字段计算（不含兜底）
        int total = data.getInt("inventory", 0);
        // 如果 inventory 不存在，尝试 stock
        if (!data.containsKey("inventory")) {
            total = data.getInt("stock", 0);
        }
        // 如果 stock 也不存在，用 avail + locked + sold
        if (!data.containsKey("inventory") && !data.containsKey("stock")) {
            total = avail + locked + sold;
        }

        if (avail < 0) return "availableStock 不能为负数";
        if (locked < 0) return "lockedStock 不能为负数";
        if (sold < 0) return "soldStock 不能为负数";

        int sum = avail + locked + sold;
        if (total != sum) {
            return "库存合计不一致: total=" + total + ", sum(avail+locked+sold)=" + sum;
        }

        String expectedStatus = stockStatus(data);
        String actualStatus = data.getStr("stockStatus", "");
        if (!expectedStatus.equals(actualStatus)) {
            return "stockStatus 不匹配: 期望=" + expectedStatus + ", 实际=" + actualStatus;
        }

        return null; // 一致
    }
}
