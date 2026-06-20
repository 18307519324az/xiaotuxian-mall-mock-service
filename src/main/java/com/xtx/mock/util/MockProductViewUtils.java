package com.xtx.mock.util;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

/**
 * MockProductViewUtils — 商品展示/规格文本工具类（无状态、纯函数）。
 * <p>
 * 职责：
 * <ul>
 *   <li>SKU specs 文本格式化（formatSpecsText）</li>
 * </ul>
 * <p>
 * 本类所有方法均为纯函数，不依赖 Spring 容器，不读写 runtime 数据。
 * 从 {@code com.xtx.mock.controller.MockController}、
 * {@code com.xtx.mock.service.goods.GoodsMockService} 和
 * {@code com.xtx.mock.service.review.ReviewMockService} 提取合并而来。
 */
public final class MockProductViewUtils {

    private MockProductViewUtils() {
    }

    /**
     * 规格 JSON 数组 → 格式化文本。
     * <p>
     * 输入示例：{@code [{"name":"颜色","valueName":"黑色"}, {"name":"尺寸","valueName":"M"}]}
     * <br>
     * 输出示例：{@code "颜色：黑色 尺寸：M"}
     * <p>
     * 规则：
     * <ol>
     *   <li>null → 返回空字符串</li>
     *   <li>非 JSONArray 且不包含 "valueName" 键 → 返回 toString()</li>
     *   <li>JSONArray → 遍历并拼接 "name：valueName"，空格分隔</li>
     *   <li>name 为空 → 只输出 valueName</li>
     *   <li>value 为空 → 跳过该项</li>
     *   <li>解析异常 → 返回 toString()</li>
     * </ol>
     *
     * @param specsObj 规格对象（JSONArray、含有 valueName 的 JSON 字符串、或其他）
     * @return 格式化规格文本
     */
    public static String formatSpecsText(Object specsObj) {
        if (specsObj == null) return "";
        try {
            JSONArray arr;
            if (specsObj instanceof JSONArray) {
                arr = (JSONArray) specsObj;
            } else {
                String raw = specsObj.toString();
                if (raw.startsWith("[") && raw.contains("valueName")) {
                    arr = JSONUtil.parseArray(raw);
                } else {
                    return raw;
                }
            }
            java.util.List<String> parts = new java.util.ArrayList<>();
            for (Object item : arr) {
                JSONObject obj = (JSONObject) item;
                String name = obj.getStr("name", "");
                String value = obj.getStr("valueName", obj.getStr("value", ""));
                if (value != null && !value.isBlank()) {
                    parts.add(name.isBlank() ? value : name + "：" + value);
                }
            }
            return String.join(" ", parts);
        } catch (Exception e) {
            return specsObj.toString();
        }
    }
}
