package com.xtx.mock.service.order;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeAddressStore;
import com.xtx.mock.store.RuntimeBenefitStore;
import com.xtx.mock.store.RuntimeCartStore;
import com.xtx.mock.store.RuntimeOrderStore;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * OrderCreateMockService — 订单创建业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>校验收货地址（存在性 + 用户归属）</li>
 *   <li>从购物车或提交商品列表构建订单商品快照</li>
 *   <li>生成不重复的 orderId 和 orderNo</li>
 *   <li>优惠券/礼品卡抵扣计算与状态变更</li>
 *   <li>订单状态确定和金额计算</li>
 *   <li>购物车已结算商品清除</li>
 *   <li>订单 + 购物车 + 优惠单持久化</li>
 * </ul>
 * <p>
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockOrderCreateController} 直接实例化。
 * 原始逻辑从 {@code MockController.orderSubmit()} 提取，行为 100% 一致。
 */
@Slf4j
public class OrderCreateMockService {

    private static final String CURRENT_USER_ID = "xiaotuxian001";

    private final RuntimeOrderStore orderStore;
    private final RuntimeCartStore cartStore;
    private final RuntimeAddressStore addressStore;
    private final RuntimeBenefitStore benefitStore;
    private final JSONObject masterSkus;
    private final JSONObject masterProducts;
    private final AtomicInteger orderCounter;
    private final BiFunction<String, String, String> imageNormalizer;
    private final String defaultFallbackImage;
    private final Function<Object, String> formatSpecsHelper;

    /**
     * 全依赖构造器。
     * <p>
     * 所有参数由 {@code MockOrderCreateController} 在首次调用时传入：
     * <ul>
     *   <li>Store 引用：OrderStore / CartStore / AddressStore / BenefitStore</li>
     *   <li>Master 数据：masterSkus, masterProducts（只读，不信任前端数据）</li>
     *   <li>ID 生成：orderCounter（与 MockController 共享的自增计数器）</li>
     *   <li>工具函数：imageNormalizer, formatSpecsHelper（委托到 MockController 原始实现）</li>
     * </ul>
     */
    public OrderCreateMockService(
            RuntimeOrderStore orderStore,
            RuntimeCartStore cartStore,
            RuntimeAddressStore addressStore,
            RuntimeBenefitStore benefitStore,
            JSONObject masterSkus,
            JSONObject masterProducts,
            AtomicInteger orderCounter,
            BiFunction<String, String, String> imageNormalizer,
            String defaultFallbackImage,
            Function<Object, String> formatSpecsHelper) {
        this.orderStore = orderStore;
        this.cartStore = cartStore;
        this.addressStore = addressStore;
        this.benefitStore = benefitStore;
        this.masterSkus = masterSkus;
        this.masterProducts = masterProducts;
        this.orderCounter = orderCounter;
        this.imageNormalizer = imageNormalizer;
        this.defaultFallbackImage = defaultFallbackImage;
        this.formatSpecsHelper = formatSpecsHelper;
    }

    // ==================== 订单创建 ====================

    /**
     * 提交订单（POST /member/order 核心逻辑）。
     * <p>
     * 完整流程：
     * <ol>
     *   <li>校验 addressId 必填且属于当前用户</li>
     *   <li>获取商品列表（rebuy 路径从 params.goods / 正常路径从 CartStore）</li>
     *   <li>生成唯一 orderId 和 orderNo</li>
     *   <li>构建订单商品快照（从 masterSkus 重新获取价格，不信任前端数据）</li>
     *   <li>读取支付参数并计算运费</li>
     *   <li>优惠券抵扣（goods / freight 类型分别处理）</li>
     *   <li>礼品卡抵扣（单卡模式）</li>
     *   <li>确定订单状态（在线支付→待付款1 / 货到付款或0元→待发货2）</li>
     *   <li>构建完整订单 JSON 并保存</li>
     *   <li>清除购物车中已结算商品</li>
     *   <li>持久化所有变更并返回响应</li>
     * </ol>
     *
     * @param userId 用户 ID（已通过 token 认证）
     * @param params 请求体参数（addressId, goods, payType, deliveryTimeType, payChannel, couponId, giftCardCode）
     * @return 订单创建结果 JSONObject（含 id, payMoney, orderState, countdown 等）
     * @throws IllegalArgumentException 参数校验失败时抛出，Controller 自动转为 FrontResponse.failure
     */
    public JSONObject createOrder(String userId, Map<String, Object> params) {
        // ── Step 1: 校验 addressId ──
        Object addressIdObj = params.get("addressId");
        if (addressIdObj == null || addressIdObj.toString().isBlank()) {
            throw new IllegalArgumentException("请选择收货地址");
        }

        // 确保地址存在且属于当前用户
        String addressId = addressIdObj.toString();
        JSONObject addrSnapshot = addressStore.getById(addressId);
        if (addrSnapshot == null) {
            throw new IllegalArgumentException("收货地址不存在");
        }
        if (!userId.equals(addrSnapshot.getStr("userId"))) {
            throw new IllegalArgumentException("收货地址不属于当前用户");
        }

        // ── Step 2: 获取商品列表 ──
        // 优先使用 params.goods（rebuy 路径），否则从 CartStore 读取 selected=true 条目
        Object goodsParam = params.get("goods");
        List<Map<String, Object>> requestGoodsList = null;
        if (goodsParam instanceof List) {
            requestGoodsList = (List<Map<String, Object>>) goodsParam;
            if (requestGoodsList.isEmpty()) requestGoodsList = null;
        }

        List<Map.Entry<String, JSONObject>> selectedEntries = new ArrayList<>();

        if (requestGoodsList != null) {
            // Rebuy 路径：从 params.goods 构建订单商品（不校验 CartStore）
            // 后端只信任 skuId 和 count，其余字段全部从 masterSkus 重新获取
            for (Map<String, Object> g : requestGoodsList) {
                Object skuIdObj = g.get("skuId");
                if (skuIdObj == null || skuIdObj.toString().isBlank()) {
                    throw new IllegalArgumentException("商品信息异常：缺少 SKU ID");
                }
                String skuId = skuIdObj.toString();

                Object countObj = g.get("count");
                int count = 1;
                if (countObj instanceof Number) {
                    count = ((Number) countObj).intValue();
                } else if (countObj instanceof String) {
                    try { count = Integer.parseInt((String) countObj); } catch (Exception e) { /* 默认 1 */ }
                }
                if (count <= 0) {
                    throw new IllegalArgumentException("商品数量异常：" + skuId);
                }

                // 从 masterSkus 获取真实商品信息（不信任前端传入的任何价格/名称）
                JSONObject sku = masterSkus.getJSONObject(skuId);
                if (sku == null) {
                    throw new IllegalArgumentException("商品不存在：" + skuId);
                }

                // 校验真实价格 > 0
                String priceStr = sku.getStr("price", "0").replace(",", "");
                BigDecimal priceBD;
                try {
                    priceBD = new BigDecimal(priceStr);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("商品价格异常：" + skuId);
                }
                if (priceBD.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("商品价格异常：" + skuId);
                }

                // 使用 masterSkus 数据构建 cartItem（不信任前端数据）
                JSONObject cartItem = new JSONObject();
                cartItem.set("id", "rebuy_" + skuId);
                cartItem.set("skuId", skuId);
                cartItem.set("goodsId", sku.getStr("goodsId", ""));
                // SKU 可能没有 name 字段，从父商品反查
                String skuName = sku.getStr("name", "");
                if (skuName.isBlank()) {
                    JSONObject parentProd = masterProducts.getJSONObject(sku.getStr("goodsId", ""));
                    if (parentProd != null) skuName = parentProd.getStr("name", "");
                }
                cartItem.set("name", skuName);
                cartItem.set("attrsText", formatSpecsHelper.apply(sku.get("specs")));
                cartItem.set("picture", sku.getStr("picture", ""));
                cartItem.set("price", priceBD.toPlainString());
                cartItem.set("nowPrice", priceBD.toPlainString());
                cartItem.set("count", count);
                cartItem.set("selected", true);
                cartItem.set("stock", sku.getInt("inventory", 0));
                cartItem.set("isEffective", true);
                cartItem.set("userId", userId);

                final String entryKey = "rebuy_" + skuId;
                final JSONObject entryValue = cartItem;
                selectedEntries.add(new Map.Entry<String, JSONObject>() {
                    @Override public String getKey() { return entryKey; }
                    @Override public JSONObject getValue() { return entryValue; }
                    @Override public JSONObject setValue(JSONObject value) { return null; }
                });
            }
        } else {
            // 正常购物车路径：从 CartStore 读取 selected=true 条目
            for (Map.Entry<String, JSONObject> entry : cartStore.getAllCarts().entrySet()) {
                JSONObject cart = entry.getValue();
                if (!userId.equals(cart.getStr("userId", CURRENT_USER_ID))) continue;
                if (cart.getBool("selected", true)) {
                    selectedEntries.add(entry);
                }
            }
        }

        if (selectedEntries.isEmpty()) {
            throw new IllegalArgumentException("没有可结算的商品");
        }

        // ── Step 3: 生成唯一订单 ID 和编号 ──
        int seq;
        String orderId;
        do {
            seq = orderCounter.incrementAndGet();
            orderId = "order_" + String.format("%03d", seq);
        } while (orderStore.getById(orderId) != null);
        String orderNo = "NO" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                         + String.format("%04d", seq);

        // ── Step 4: 构建订单商品快照 + BigDecimal 计算金额 ──
        JSONArray orderSkus = new JSONArray();
        BigDecimal totalMoney = BigDecimal.ZERO;
        int totalNum = 0;
        List<String> clearedCartIds = new ArrayList<>();

        for (Map.Entry<String, JSONObject> entry : selectedEntries) {
            JSONObject cart = entry.getValue();
            String skuId = cart.getStr("skuId");
            String goodsId = cart.getStr("goodsId");
            int count = cart.getInt("count", 1);

            // 从 masterSkus 重新获取价格（不信任 cart 中任何可能被污染的字段）
            JSONObject skuForPrice = masterSkus.getJSONObject(skuId);
            String priceStr;
            if (skuForPrice != null) {
                priceStr = skuForPrice.getStr("price", "0").replace(",", "");
            } else {
                priceStr = cart.getStr("nowPrice", "0").replace(",", "");
            }

            BigDecimal priceBD;
            try {
                priceBD = new BigDecimal(priceStr);
            } catch (NumberFormatException e) {
                continue;
            }
            if (priceBD.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal itemTotal = priceBD.multiply(BigDecimal.valueOf(count));

            // 获取 SPU ID
            JSONObject product = masterProducts.getJSONObject(goodsId);
            String spuId = goodsId;
            if (product != null) {
                spuId = product.getStr("spuId", goodsId);
            }

            // 图片兜底
            String cartPic = cart.getStr("picture", "");
            if (cartPic == null || cartPic.isBlank()) {
                JSONObject sku = masterSkus.getJSONObject(skuId);
                cartPic = sku != null ? sku.getStr("picture", "") : "";
            }

            JSONObject skuItem = new JSONObject();
            skuItem.set("id", cart.getStr("id"));
            skuItem.set("skuId", skuId);
            skuItem.set("spuId", spuId);
            skuItem.set("goodsId", goodsId);
            skuItem.set("name", cart.getStr("name"));
            skuItem.set("attrsText", cart.getStr("attrsText"));
            String normalizedPic = imageNormalizer.apply(cartPic, defaultFallbackImage);
            skuItem.set("image", normalizedPic);
            skuItem.set("picture", normalizedPic);
            skuItem.set("price", priceBD.toPlainString());
            skuItem.set("nowPrice", priceBD.toPlainString());
            skuItem.set("count", count);
            skuItem.set("quantity", count);
            String itemTotalStr = itemTotal.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString();
            skuItem.set("totalMoney", itemTotalStr);
            skuItem.set("totalPayMoney", itemTotalStr);
            skuItem.set("realPay", itemTotalStr);
            skuItem.set("curPrice", priceBD.toPlainString());
            orderSkus.add(skuItem);

            totalMoney = totalMoney.add(itemTotal);
            totalNum += count;
            clearedCartIds.add(entry.getKey());
        }

        // 校验总金额 > 0
        if (totalMoney.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("订单金额异常，不能提交");
        }

        // ── Step 5/6: 读取支付参数 + 运费 ──
        int payType = 1;
        if (params.get("payType") != null) {
            payType = Integer.parseInt(params.get("payType").toString());
        }
        int deliveryTimeType = 1;
        if (params.get("deliveryTimeType") != null) {
            deliveryTimeType = Integer.parseInt(params.get("deliveryTimeType").toString());
        }
        Object payChannel = params.get("payChannel");
        if (payChannel == null) payChannel = 1;

        // 运费：货到付款收 5 元手续费
        BigDecimal postFeeBD = BigDecimal.ZERO;
        if (payType == 2) {
            postFeeBD = new BigDecimal("5.00");
        }
        BigDecimal goodsAmount = totalMoney;
        BigDecimal currentPostFee = postFeeBD;
        BigDecimal discountGoodsBD = BigDecimal.ZERO;
        BigDecimal discountFreightBD = BigDecimal.ZERO;
        String couponId = null;
        String couponName = null;
        String couponType = null;

        // ── Step 6: 优惠券抵扣 ──
        Object couponIdParam = params.get("couponId");
        if (couponIdParam != null && !couponIdParam.toString().isBlank()) {
            String inputCouponId = couponIdParam.toString();
            JSONArray userCoupons = benefitStore.getCoupons(userId);
            if (userCoupons != null) {
                for (Object obj : userCoupons) {
                    JSONObject c = (JSONObject) obj;
                    if (inputCouponId.equals(c.getStr("id")) && "available".equals(c.getStr("status"))) {
                        int threshold = c.getInt("threshold", 0);
                        BigDecimal thresholdBD = new BigDecimal(String.valueOf(threshold));
                        if (totalMoney.compareTo(thresholdBD) >= 0) {
                            couponId = inputCouponId;
                            couponName = c.getStr("name");
                            couponType = c.getStr("couponType", "goods");
                            BigDecimal amount = new BigDecimal(String.valueOf(c.getInt("amount", 0)));
                            if ("freight".equals(couponType)) {
                                // 运费券：最多抵扣当前运费金额
                                discountFreightBD = amount.compareTo(currentPostFee) > 0 ? currentPostFee : amount;
                                currentPostFee = currentPostFee.subtract(discountFreightBD);
                                if (currentPostFee.compareTo(BigDecimal.ZERO) < 0) currentPostFee = BigDecimal.ZERO;
                            } else {
                                // 商品券：最多抵扣当前商品金额
                                discountGoodsBD = amount.compareTo(goodsAmount) > 0 ? goodsAmount : amount;
                                goodsAmount = goodsAmount.subtract(discountGoodsBD);
                                if (goodsAmount.compareTo(BigDecimal.ZERO) < 0) goodsAmount = BigDecimal.ZERO;
                            }
                            // 标记优惠券为已使用
                            c.set("status", "used");
                        }
                        break;
                    }
                }
            }
        }

        // ── Step 7: 礼品卡抵扣 ──
        BigDecimal payMoney = goodsAmount.add(currentPostFee);
        BigDecimal giftCardAmountBD = BigDecimal.ZERO;
        String appliedGcCode = null;
        Object giftCardCodeParam = params.get("giftCardCode");
        if (giftCardCodeParam != null && !giftCardCodeParam.toString().isBlank()) {
            String inputGcCode = giftCardCodeParam.toString().trim().toUpperCase();
            JSONArray userGiftCards = benefitStore.getGiftCards(userId);
            if (userGiftCards != null) {
                for (Object obj : userGiftCards) {
                    JSONObject gc = (JSONObject) obj;
                    if (inputGcCode.equals(gc.getStr("code")) && "active".equals(gc.getStr("status"))) {
                        int gcBalance = gc.getInt("balance", 0);
                        if (gcBalance > 0 && payMoney.compareTo(BigDecimal.ZERO) > 0) {
                            appliedGcCode = inputGcCode;
                            BigDecimal available = BigDecimal.valueOf(gcBalance);
                            giftCardAmountBD = available.compareTo(payMoney) > 0 ? payMoney : available;
                            payMoney = payMoney.subtract(giftCardAmountBD);
                            if (payMoney.compareTo(BigDecimal.ZERO) < 0) payMoney = BigDecimal.ZERO;
                            int newBalance = gcBalance - giftCardAmountBD.intValue();
                            gc.set("balance", Math.max(newBalance, 0));
                            if (gc.getInt("balance", 0) <= 0) gc.set("status", "used");
                        }
                        break;
                    }
                }
            }
        }

        // 持久化优惠券和礼品卡状态变更
        benefitStore.save();

        // ── Step 8: 最终校验与订单状态 ──
        if (totalMoney.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("订单金额异常，不能提交");
        }
        if (payMoney.compareTo(BigDecimal.ZERO) < 0) {
            payMoney = BigDecimal.ZERO;
        }

        // 订单状态：在线支付且 payMoney>0 → 待付款(1)，货到付款或 0 元 → 待发货(2)
        int orderState;
        if (payType == 2 || payMoney.compareTo(BigDecimal.ZERO) <= 0) {
            orderState = 2;
        } else {
            orderState = 1;
        }

        if (orderSkus.isEmpty()) {
            throw new IllegalArgumentException("没有可结算的商品");
        }

        // ── Step 9: 构建完整订单 JSON ──
        JSONObject order = new JSONObject();
        order.set("id", orderId);
        order.set("orderNo", orderNo);
        order.set("userId", userId);
        order.set("orderState", orderState);
        order.set("totalMoney", totalMoney.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
        order.set("totalNum", totalNum);
        order.set("payMoney", payMoney.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
        order.set("postFee", postFeeBD.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
        if (couponId != null) {
            order.set("couponId", couponId);
            order.set("couponName", couponName);
            order.set("couponType", couponType);
            order.set("discountGoodsAmount", discountGoodsBD.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
            order.set("discountFreightAmount", discountFreightBD.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
            order.set("discountAmount", discountGoodsBD.add(discountFreightBD).setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
        }
        if (appliedGcCode != null) {
            order.set("giftCardAmount", giftCardAmountBD.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
            order.set("giftCardCode", appliedGcCode);
        }
        order.set("payChannel", payChannel);
        order.set("payType", payType);
        order.set("deliveryTimeType", deliveryTimeType);
        order.set("createTime", java.time.LocalDateTime.now().toString());
        order.set("payLatestTime", java.time.LocalDateTime.now().plusHours(1).toString());
        order.set("payExpireTime", java.time.LocalDateTime.now().plusHours(1).toString());
        order.set("skus", orderSkus);
        if (payMoney.compareTo(BigDecimal.ZERO) <= 0) {
            order.set("countdown", -1);
        } else {
            order.set("countdown", 1800);
        }

        // 保存收货地址快照
        order.set("receiverAddress", new JSONObject()
            .set("receiver", addrSnapshot.getStr("receiver", ""))
            .set("contact", addrSnapshot.getStr("contact", ""))
            .set("fullLocation", addrSnapshot.getStr("fullLocation", ""))
            .set("address", addrSnapshot.getStr("address", ""))
        );

        // 货到付款直接记录支付时间
        if (payType == 2) {
            order.set("payTime", java.time.LocalDateTime.now().toString());
        }

        // ── Step 10: 保存到运行时订单 ──
        orderStore.put(orderId, order);

        // ── Step 11: 清除购物车中已结算商品 ──
        Map<String, JSONObject> allCarts = cartStore.getAllCarts();
        List<String> cleanupCartIds = new ArrayList<>();
        for (Map.Entry<String, JSONObject> entry : allCarts.entrySet()) {
            JSONObject cart = entry.getValue();
            if (!userId.equals(cart.getStr("userId", CURRENT_USER_ID))) continue;
            if (!cart.getBool("selected", true)) continue;

            if (requestGoodsList != null) {
                // rebuy 路径：只清除 SKU 在提交商品列表中的条目
                String cartSkuId = cart.getStr("skuId");
                boolean skuMatches = false;
                for (Map<String, Object> g : requestGoodsList) {
                    if (g.get("skuId") != null && g.get("skuId").toString().equals(cartSkuId)) {
                        skuMatches = true;
                        break;
                    }
                }
                if (!skuMatches) continue;
            }
            cleanupCartIds.add(entry.getKey());
        }
        for (String cartId : cleanupCartIds) {
            cartStore.removeCart(cartId);
        }

        // ── Step 12: 构建响应 ──
        JSONObject result = new JSONObject();
        result.set("id", orderId);
        result.set("payMoney", String.format("%.2f", payMoney));
        result.set("orderState", orderState);
        if (payMoney.compareTo(BigDecimal.ZERO) <= 0) {
            result.set("countdown", -1);
        } else {
            result.set("countdown", 1800);
        }
        if (appliedGcCode != null) {
            result.set("giftCardAmount", giftCardAmountBD.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
        }
        // 优惠券信息（含 null 值）
        result.set("couponId", couponId);
        result.set("couponName", couponName);
        result.set("couponType", couponType);
        if (discountGoodsBD != null) {
            result.set("discountGoodsAmount", discountGoodsBD.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
            result.set("discountFreightAmount", discountFreightBD.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
            result.set("discountAmount", discountGoodsBD.add(discountFreightBD).setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
        }

        // ── Step 13: 持久化所有变更 ──
        orderStore.saveOrders();
        cartStore.save();

        return result;
    }
}
