package com.xtx.mock.service.points;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimePointsStore;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * PointsMockService — 积分模块业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>查询积分首页数据（balance, level, expiringSoon）</li>
 *   <li>重置当前用户积分</li>
 *   <li>计算当前积分余额</li>
 *   <li>计算等级</li>
 *   <li>计算即将过期积分</li>
 *   <li>执行签到加积分</li>
 *   <li>执行邀请奖励加积分</li>
 *   <li>执行抽奖扣积分</li>
 *   <li>执行 FIFO 扣减</li>
 *   <li>提供给 GameMockService / InviteMockService 调用</li>
 * </ul>
 * <p>
 * 保持旧响应结构不变。
 */
@Slf4j
public class PointsMockService {

    private final RuntimePointsStore store;

    public PointsMockService(RuntimePointsStore store) {
        this.store = store;
    }

    /**
     * 从积分流水动态计算：balance, level, expiringSoon。
     * <p>
     * 核心规则：
     * <ul>
     *   <li>balance = 所有未过期正向记录的 remaining 之和</li>
     *   <li>expiringSoon = 未来 30 天内 expireAt 的正向记录 remaining 之和</li>
     *   <li>level = 根据 balance 计算</li>
     *   <li>消耗记录（delta<0）无 remaining，不计入 balance</li>
     *   <li>已过期记录（expireAt < now）不计入 balance 和 expiringSoon</li>
     * </ul>
     */
    public void computeSummary(JSONObject points) {
        if (points == null) return;
        JSONArray records = points.getJSONArray("records");
        if (records == null) {
            points.set("balance", 0);
            points.set("level", "普通会员");
            points.set("expiringSoon", 0);
            return;
        }
        int balance = 0;
        int expiringSoon = 0;
        String now = java.time.LocalDateTime.now().toString();
        String in30Days = java.time.LocalDateTime.now().plusDays(30).toString();

        for (int i = 0; i < records.size(); i++) {
            JSONObject rec = records.getJSONObject(i);
            int remaining = rec.getInt("remaining", 0);
            if (remaining <= 0) continue; // 已用完或消耗记录

            String expireAt = rec.getStr("expireAt", "");
            // 跳过已过期记录
            if (!expireAt.isBlank() && expireAt.compareTo(now) < 0) {
                continue;
            }

            // 未过期的剩余积分计入 balance
            balance += remaining;

            // 未来 30 天内到期的计入 expiringSoon
            if (!expireAt.isBlank() && expireAt.compareTo(in30Days) <= 0) {
                expiringSoon += remaining;
            }
        }

        // 等级阈值
        String level;
        if (balance >= 10000) level = "钻石会员";
        else if (balance >= 3000) level = "白金会员";
        else if (balance >= 1000) level = "黄金会员";
        else if (balance >= 500) level = "白银会员";
        else level = "普通会员";

        points.set("balance", balance);
        points.set("level", level);
        points.set("expiringSoon", expiringSoon);
    }

    /**
     * FIFO（先进先出）扣减正向积分的 remaining。
     * 按 expireAt 升序排列正向记录，从最先过期的开始扣减。
     *
     * @param records 积分流水数组
     * @param amount  需要扣减的积分
     * @return true 扣减成功；false 可用积分不足
     */
    public boolean deductFIFO(JSONArray records, int amount) {
        // 收集所有剩余 > 0 的正向记录
        java.util.List<JSONObject> positiveList = new java.util.ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            JSONObject rec = records.getJSONObject(i);
            String type = rec.getStr("type", "");
            if (!"income".equals(type)) continue;
            int remaining = rec.getInt("remaining", 0);
            if (remaining <= 0) continue;
            positiveList.add(rec);
        }
        // 按 expireAt 升序排列（空 expireAt 放最后）
        positiveList.sort((a, b) -> {
            String ea = a.getStr("expireAt", "");
            String eb = b.getStr("expireAt", "");
            if (ea.isBlank() && eb.isBlank()) return 0;
            if (ea.isBlank()) return 1;
            if (eb.isBlank()) return -1;
            return ea.compareTo(eb);
        });

        int need = amount;
        for (JSONObject rec : positiveList) {
            if (need <= 0) break;
            int curRemaining = rec.getInt("remaining", 0);
            int deduct = Math.min(curRemaining, need);
            rec.set("remaining", curRemaining - deduct);
            need -= deduct;
        }
        return need <= 0;
    }

    /**
     * 奖励积分给指定用户。
     *
     * @param userId     用户 ID
     * @param amount     奖励积分数量
     * @param title      积分记录标题
     * @param reason     积分原因（sign/invite/lottery_prize）
     * @param expireDays 有效期天数（正向积分必须设置）
     * @param counter    ID 生成器
     * @return 创建的积分记录 ID
     */
    public String award(String userId, int amount, String title, String reason,
                        int expireDays, AtomicInteger counter) {
        JSONObject points = store.getPoints(userId);
        JSONArray records = points.getJSONArray("records");
        if (records == null) {
            records = new JSONArray();
            points.set("records", records);
        }
        String now = java.time.LocalDateTime.now().toString().replace('T', ' ').substring(0, 19);
        String expireAt;
        if (expireDays > 0) {
            expireAt = java.time.LocalDateTime.now().plusDays(expireDays)
                    .toString().replace('T', ' ').substring(0, 19);
        } else {
            expireAt = "";
        }
        String recordId = "points_" + counter.incrementAndGet();
        JSONObject rec = new JSONObject()
                .set("id", recordId)
                .set("title", title)
                .set("reason", reason)
                .set("delta", amount)
                .set("type", "income")
                .set("remaining", amount)
                .set("source", reason)
                .set("createdAt", now);
        if (!expireAt.isBlank()) {
            rec.set("expireAt", expireAt);
        } else {
            rec.set("expireAt", "");
        }
        records.add(0, rec);
        return recordId;
    }

    /**
     * 扣减用户积分（创建消费记录）。
     *
     * @param userId  用户 ID
     * @param amount  扣减数量（正数）
     * @param title   积分记录标题
     * @param reason  积分原因（lottery）
     * @param counter ID 生成器
     * @return 创建的积分记录 ID，扣减失败返回 null
     */
    public String deduct(String userId, int amount, String title, String reason,
                         AtomicInteger counter) {
        JSONObject points = store.getPoints(userId);
        JSONArray records = points.getJSONArray("records");
        if (records == null) {
            records = new JSONArray();
            points.set("records", records);
        }
        // 先计算当前余额
        computeSummary(points);
        int balance = points.getInt("balance", 0);
        if (balance < amount) {
            return null;
        }
        // FIFO 扣减 remaining
        if (!deductFIFO(records, amount)) {
            return null;
        }
        // 添加消费记录
        String now = java.time.LocalDateTime.now().toString().replace('T', ' ').substring(0, 19);
        String recordId = "points_" + counter.incrementAndGet();
        records.add(0, new JSONObject()
                .set("id", recordId)
                .set("title", title)
                .set("reason", reason)
                .set("delta", -amount)
                .set("type", "expense")
                .set("source", reason)
                .set("createdAt", now)
                .set("expireAt", ""));
        return recordId;
    }

    /**
     * 获取指定用户当前积分余额。
     */
    public int getBalance(String userId) {
        JSONObject points = store.getPoints(userId);
        computeSummary(points);
        return points.getInt("balance", 0);
    }

    /**
     * 获取指定用户即将过期积分。
     */
    public int getExpiringSoon(String userId) {
        JSONObject points = store.getPoints(userId);
        computeSummary(points);
        return points.getInt("expiringSoon", 0);
    }

    /**
     * 重置用户积分（清空记录）。
     */
    public void resetPoints(String userId) {
        store.resetPoints(userId);
    }
}
