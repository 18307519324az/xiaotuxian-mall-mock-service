package com.xtx.mock.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeBenefitStore;
import com.xtx.mock.store.RuntimeGameStore;
import lombok.extern.slf4j.Slf4j;

import java.util.Random;

/**
 * GameMockService — 抽奖和签到业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>加权随机抽奖（基于概率权重选择奖品）</li>
 *   <li>签到去重检查</li>
 *   <li>抽奖/签到涉及积分操作，由调用方（MockGameController）协调 runtimePoints</li>
 * </ul>
 * <p>
 * 不直接读写文件，委托 RuntimeGameStore 持久化。
 * 积分操作（扣除/奖励/保存）由 MockController 通过注入的 runtimePoints 处理。
 */
@Slf4j
public class GameMockService {

    private final RuntimeGameStore store;

    public GameMockService(RuntimeGameStore store) {
        this.store = store;
    }

    /**
     * 基于概率权重的加权随机抽奖。
     *
     * @param prizes 奖品列表（每个元素含 probability 权重字段，总和通常为 100）
     * @return 选中的奖品对象
     */
    public JSONObject drawPrize(JSONArray prizes) {
        int totalWeight = 0;
        for (int i = 0; i < prizes.size(); i++) {
            totalWeight += prizes.getJSONObject(i).getInt("probability", 0);
        }
        int rand = new Random().nextInt(totalWeight);
        int cumulative = 0;
        JSONObject prize = prizes.getJSONObject(0); // fallback
        for (int i = 0; i < prizes.size(); i++) {
            cumulative += prizes.getJSONObject(i).getInt("probability", 0);
            if (rand < cumulative) {
                prize = prizes.getJSONObject(i);
                break;
            }
        }
        return prize;
    }

    /**
     * 检查用户今日是否已签到。
     *
     * @param uid   当前用户 ID
     * @param today 今日日期字符串 (yyyy-MM-dd)
     * @return true 表示今日已签到
     */
    public boolean isSignedToday(String uid, String today) {
        JSONArray userSignins = store.getSigninRecords(uid);
        if (userSignins != null) {
            for (int i = 0; i < userSignins.size(); i++) {
                JSONObject rec = userSignins.getJSONObject(i);
                if (today.equals(rec.getStr("date"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 从 points records 中检查今日是否已签到（双重验证）。
     *
     * @param records 积分流水记录
     * @param today   今日日期字符串 (yyyy-MM-dd)
     * @return true 表示今日已签到
     */
    public boolean isSignedTodayFromRecords(JSONArray records, String today) {
        if (records != null) {
            for (int i = 0; i < records.size(); i++) {
                JSONObject rec = records.getJSONObject(i);
                String reason = rec.getStr("reason", "");
                String source = rec.getStr("source", "");
                if ("sign".equals(reason) || "signin".equals(source)) {
                    String createdAt = rec.getStr("createdAt", "");
                    if (createdAt.startsWith(today)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 格式化抽奖数据响应（不含 pointBalance/chances 动态字段，由 controller 设置）。
     */
    public JSONObject formatLotteryResponse(JSONObject lottery) {
        JSONObject result = new JSONObject();
        result.set("prizes", lottery.getJSONArray("prizes"));
        result.set("history", lottery.getJSONArray("history"));
        result.set("rules", lottery.getJSONArray("rules"));
        return result;
    }
}
