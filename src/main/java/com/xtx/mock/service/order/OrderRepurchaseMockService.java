package com.xtx.mock.service.order;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeAddressStore;
import com.xtx.mock.store.RuntimeOrderStore;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.function.Function;

/**
 * OrderRepurchaseMockService — 再次购买业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>根据已有订单生成再购买预订单数据</li>
 *   <li>从 masterSkus 获取最新价格（不信任订单快照价格）</li>
 *   <li>构建 orderPre 风格的响应结构（goods、summary、userAddresses）</li>
 * </ul>
 * <p>
 * 不修改 runtime 数据（只读操作）。
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockOrderRepurchaseController} 直接实例化。
 */
@Slf4j
public class OrderRepurchaseMockService {

    private final RuntimeOrderStore orderStore;
    private final JSONObject masterSkus;
    private final RuntimeAddressStore addressStore;
    private final Function<Object, String> specsFormatter;
    private final java.util.function.BiFunction<String, String, String> imageNormalizer;
    private final String defaultFallbackImage;

    public OrderRepurchaseMockService(RuntimeOrderStore orderStore,
                                       JSONObject masterSkus,
                                       RuntimeAddressStore addressStore,
                                       Function<Object, String> specsFormatter,
                                       java.util.function.BiFunction<String, String, String> imageNormalizer,
                                       String defaultFallbackImage) {
        this.orderStore = orderStore;
        this.masterSkus = masterSkus;
        this.addressStore = addressStore;
        this.specsFormatter = specsFormatter;
        this.imageNormalizer = imageNormalizer;
        this.defaultFallbackImage = defaultFallbackImage;
    }

    /**
     * 根据已有订单构建再购买预订单数据。
     *
     * @param orderId 要复购的订单 ID
     * @param uid     当前用户 ID
     * @return 预订单 JSONObject，包含 goods/summary/userAddresses；
     *         如果订单不存在或不属于该用户返回 null
     */
    public JSONObject buildRepurchase(String orderId, String uid) {
        JSONObject order = orderStore.getById(orderId);
        if (!orderStore.isOrderOwner(order, uid)) {
            return null;
        }

        // 从订单中提取 skus 信息
        JSONArray orderSkus = order.getJSONArray("skus");
        if (orderSkus == null) orderSkus = new JSONArray();

        // 构建 orderPre 风格的 goods 列表
        JSONArray goods = buildGoodsList(orderSkus);
        if (goods == null) goods = new JSONArray();

        // BigDecimal 计算总金额和总数量
        BigDecimal totalAmount = BigDecimal.ZERO;
        int totalGoodsCount = 0;
        for (Object obj : goods) {
            JSONObject g = (JSONObject) obj;
            int count = g.getInt("count", 1);
            String priceStr = g.getStr("price", "0").replace(",", "");
            try {
                BigDecimal price = new BigDecimal(priceStr);
                totalAmount = totalAmount.add(price.multiply(BigDecimal.valueOf(count)));
            } catch (NumberFormatException ignored) {}
            totalGoodsCount += count;

            // 确保每个商品有 totalPrice 和 totalPayPrice 字段
            String gTotalPrice = g.getStr("totalPrice", "0");
            if (gTotalPrice == null || gTotalPrice.isBlank() || "0".equals(gTotalPrice)) {
                try {
                    BigDecimal lineTotal = new BigDecimal(priceStr).multiply(BigDecimal.valueOf(count));
                    g.set("totalPrice", lineTotal.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
                } catch (NumberFormatException ignored2) {}
            }
            g.set("totalPayPrice", g.getStr("totalPrice", "0"));
        }

        // 构建完整 orderPre 结构
        JSONObject result = new JSONObject();
        result.set("goods", goods);
        result.set("totalMoney", totalAmount.stripTrailingZeros().toPlainString());
        result.set("sumMoney", totalAmount.stripTrailingZeros().toPlainString());
        result.set("totalNum", goods.size());

        // 地址列表（按用户过滤）
        JSONArray userAddresses = addressStore.listByUserId(uid);
        result.set("userAddresses", userAddresses);

        // 构建与 orderPre 一致的 summary 对象
        JSONObject summary = new JSONObject();
        summary.set("goodsCount", totalGoodsCount);
        summary.set("totalPrice", totalAmount.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
        summary.set("postFee", "0.00");
        summary.set("discountPrice", "0.00");
        summary.set("totalPayPrice", totalAmount.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
        result.set("summary", summary);

        return result;
    }

    /**
     * 从订单 SKU 快照构建 goods 列表（价格从 masterSkus 重新获取）。
     */
    private JSONArray buildGoodsList(JSONArray orderSkus) {
        JSONArray goods = new JSONArray();
        for (Object obj : orderSkus) {
            JSONObject orderSku = (JSONObject) obj;
            String skuId = orderSku.getStr("skuId", "");
            JSONObject sku = masterSkus.getJSONObject(skuId);
            if (sku == null) continue;

            JSONObject item = new JSONObject();
            String goodsId = sku.getStr("goodsId", "");
            item.set("id", sku.getStr("id", ""));
            item.set("skuId", sku.getStr("id", ""));
            item.set("goodsId", goodsId);
            item.set("name", sku.getStr("name", ""));
            String attrsText = orderSku.getStr("attrsText", "");
            if (attrsText == null || attrsText.isBlank()) {
                attrsText = specsFormatter.apply(orderSku.get("skuSpecs"));
                if (attrsText == null || attrsText.isBlank()) {
                    attrsText = specsFormatter.apply(sku.get("specs"));
                }
            }
            item.set("attrsText", attrsText != null ? attrsText : "");
            // 优先使用订单快照的图片，其次 sku 主数据
            String pic = orderSku.getStr("picture", "");
            if (pic == null || pic.isBlank()) pic = orderSku.getStr("image", "");
            if (pic == null || pic.isBlank()) pic = sku.getStr("picture", "");
            item.set("picture", imageNormalizer.apply(pic, defaultFallbackImage));
            // 价格必须从 masterSkus 重新获取（不信任订单快照）
            String realPrice = sku.getStr("price", "0");
            item.set("price", realPrice);
            item.set("nowPrice", realPrice);
            int count = orderSku.getInt("count", 1);
            if (count <= 0) count = 1;
            item.set("count", count);
            item.set("stock", sku.getInt("inventory", 100));
            item.set("isEffective", true);
            // 从 masterSkus 计算 totalPrice/totalPayPrice（BigDecimal）
            try {
                BigDecimal realPriceBD = new BigDecimal(realPrice.replace(",", ""));
                BigDecimal lineTotal = realPriceBD.multiply(BigDecimal.valueOf(count));
                String totalStr = lineTotal.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString();
                item.set("totalPrice", totalStr);
                item.set("totalPayPrice", totalStr);
            } catch (NumberFormatException e) {
                item.set("totalPrice", "0.00");
                item.set("totalPayPrice", "0.00");
            }
            goods.add(item);
        }
        return goods;
    }
}
