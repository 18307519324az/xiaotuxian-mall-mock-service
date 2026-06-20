package com.xtx.mock.service.order;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.support.MockJsonPersistence;
import com.xtx.mock.support.MockRuntimePaths;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 库存变更日志服务。
 * <p>
 * 记录每次库存变化的详情，包括变更类型、变更前后库存值。
 * 日志持久化到 data/stock-change-log-runtime.json。
 * <p>
 * 变更类型：
 * <ul>
 *   <li>LOCK    — 下单锁定库存</li>
 *   <li>CONFIRM — 支付确认扣减</li>
 *   <li>RELEASE — 取消订单释放库存</li>
 *   <li>RESET   — 重置库存</li>
 * </ul>
 */
@Slf4j
public class StockChangeLogService {

    public static final String CHANGE_TYPE_LOCK = "LOCK";
    public static final String CHANGE_TYPE_CONFIRM = "CONFIRM";
    public static final String CHANGE_TYPE_RELEASE = "RELEASE";
    public static final String CHANGE_TYPE_RESET = "RESET";

    private static final String LOG_PATH = MockRuntimePaths.STOCK_CHANGE_LOG;

    /** 日志条目集合（内存态），keyed by id */
    private final Map<String, JSONObject> logEntries = new ConcurrentHashMap<>();

    /** 自增序列 */
    private int seq = 0;

    public StockChangeLogService() {
        loadFromFile();
    }

    /**
     * 记录库存变更。
     */
    public void record(String orderNo, String skuId, String goodsId,
                       String changeType, int changeAmount,
                       int beforeAvailableStock, int afterAvailableStock,
                       int beforeLockedStock, int afterLockedStock,
                       int beforeSoldStock, int afterSoldStock) {
        String id = "stock_log_" + (++seq);
        JSONObject entry = new JSONObject();
        entry.set("id", id);
        entry.set("orderNo", orderNo);
        entry.set("skuId", skuId);
        entry.set("goodsId", goodsId);
        entry.set("changeType", changeType);
        entry.set("changeAmount", changeAmount);
        entry.set("beforeAvailableStock", beforeAvailableStock);
        entry.set("afterAvailableStock", afterAvailableStock);
        entry.set("beforeLockedStock", beforeLockedStock);
        entry.set("afterLockedStock", afterLockedStock);
        entry.set("beforeSoldStock", beforeSoldStock);
        entry.set("afterSoldStock", afterSoldStock);
        entry.set("operator", "system");
        entry.set("createTime", LocalDateTime.now().toString());
        logEntries.put(id, entry);
        save();
    }

    /**
     * 获取所有日志条目。
     */
    public JSONArray getAll() {
        JSONArray arr = new JSONArray();
        for (JSONObject entry : logEntries.values()) {
            arr.add(entry);
        }
        return arr;
    }

    /**
     * 清空所有日志。
     */
    public void clearAll() {
        logEntries.clear();
        seq = 0;
        save();
    }

    /**
     * 从文件加载日志。
     */
    private void loadFromFile() {
        JSONArray arr = MockJsonPersistence.loadArray(LOG_PATH);
        if (arr == null) return;
        logEntries.clear();
        int maxSeq = 0;
        for (Object obj : arr) {
            JSONObject entry = (JSONObject) obj;
            String id = entry.getStr("id", "");
            logEntries.put(id, entry);
            String numPart = id.replaceAll("[^0-9]", "");
            try {
                int s = Integer.parseInt(numPart);
                if (s > maxSeq) maxSeq = s;
            } catch (Exception ignored) {}
        }
        seq = maxSeq;
        log.info("Loaded {} stock change log entries, seq={}", logEntries.size(), seq);
    }

    /**
     * 持久化日志到文件。
     */
    private void save() {
        JSONArray arr = getAll();
        MockJsonPersistence.save(LOG_PATH, arr);
    }
}
