package com.xtx.mock.service.checkout;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeAddressStore;
import com.xtx.mock.store.RuntimeBenefitStore;
import com.xtx.mock.store.RuntimeCartStore;
import com.xtx.mock.util.MockImageUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * CheckoutMockService — 预结算业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>从购物车读取 selected=true 条目生成订单预览</li>
 *   <li>商品金额计算</li>
 *   <li>Summary 结构组装</li>
 *   <li>可用优惠券过滤</li>
 *   <li>地址列表读取</li>
 * </ul>
 * <p>
 * 实时计算，无运行时持久化状态。
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockCheckoutController} 直接实例化。
 */
@Slf4j
public class CheckoutMockService {

    private static final String DEFAULT_FALLBACK_IMAGE =
            "https://yanxuan-item.nosdn.127.net/3c3d99a0c5ac040408f66de1daabb206.png";

    private final RuntimeCartStore cartStore;
    private final RuntimeAddressStore addressStore;
    private final RuntimeBenefitStore benefitStore;

    public CheckoutMockService(RuntimeCartStore cartStore,
                                RuntimeAddressStore addressStore,
                                RuntimeBenefitStore benefitStore) {
        this.cartStore = cartStore;
        this.addressStore = addressStore;
        this.benefitStore = benefitStore;
    }

    /**
     * 生成订单预览。
     *
     * @param uid          用户 ID
     * @param masterSkus   SKU 主数据（用于图片补全）
     * @return 订单预览 JSONObject（包含 userAddresses, goods, summary, coupons）
     */
    public JSONObject queryOrderPre(String uid, JSONObject masterSkus) {
        JSONObject result = new JSONObject();

        // 地址列表
        JSONArray userAddresses = addressStore.listByUserId(uid);
        result.set("userAddresses", userAddresses);

        // 商品列表 + 金额计算
        JSONArray goods = new JSONArray();
        double totalPrice = 0;
        int totalCount = 0;

        for (JSONObject cart : cartStore.getSelectedCartsByUserId(uid)) {
            String skuId = cart.getStr("skuId");
            JSONObject sku = masterSkus != null ? masterSkus.getJSONObject(skuId) : null;
            int count = cart.getInt("count", 1);
            double price = Double.parseDouble(cart.getStr("price", "0"));
            double total = price * count;

            JSONObject g = new JSONObject();
            String orderPrePic = cart.getStr("picture", "");
            if (orderPrePic == null || orderPrePic.isBlank()) {
                orderPrePic = sku != null ? sku.getStr("picture", "") : "";
            }
            g.set("skuId", skuId);
            g.set("goodsId", cart.getStr("goodsId"));
            g.set("picture", MockImageUtils.normalizeImageUrl(orderPrePic, DEFAULT_FALLBACK_IMAGE));
            g.set("name", cart.getStr("name"));
            g.set("attrsText", cart.getStr("attrsText"));
            g.set("price", cart.getStr("price"));
            g.set("count", count);
            g.set("totalPrice", String.format("%.2f", total));
            g.set("totalPayPrice", String.format("%.2f", total));
            goods.add(g);

            totalPrice += total;
            totalCount += count;
        }
        result.set("goods", goods);

        // Summary
        JSONObject summary = new JSONObject();
        summary.set("goodsCount", totalCount);
        summary.set("totalPrice", String.format("%.2f", totalPrice));
        summary.set("postFee", "0.00");
        summary.set("discountPrice", "0.00");
        summary.set("totalPayPrice", String.format("%.2f", totalPrice));
        result.set("summary", summary);

        // 可用优惠券列表（仅 available 状态，满足门槛条件）
        JSONArray userCoupons = benefitStore.getCoupons(uid);
        JSONArray availableCoupons = new JSONArray();
        if (userCoupons != null) {
            for (Object obj : userCoupons) {
                JSONObject c = (JSONObject) obj;
                if ("available".equals(c.getStr("status"))) {
                    int threshold = c.getInt("threshold", 0);
                    if (totalPrice >= threshold) {
                        availableCoupons.add(c);
                    }
                }
            }
        }
        result.set("coupons", availableCoupons);

        return result;
    }

}
