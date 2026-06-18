package com.xtx.mock.service.cart;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeCartStore;
import com.xtx.mock.util.MockImageUtils;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CartMockService — 购物车业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>购物车列表查询（按 userId 过滤，SKU 图片补全）</li>
 *   <li>添加购物车（去重累加，从 master 数据补全）</li>
 *   <li>删除购物车条目</li>
 *   <li>更新购物车（selected/count）</li>
 *   <li>合并购物车（去重累加）</li>
 *   <li>批量全选/取消全选</li>
 * </ul>
 * <p>
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockCartController} 直接实例化。
 */
@Slf4j
public class CartMockService {

    private static final String DEFAULT_FALLBACK_IMAGE = "https://yanxuan-item.nosdn.127.net/3c3d99a0c5ac040408f66de1daabb206.png";

    private final RuntimeCartStore store;

    public CartMockService(RuntimeCartStore store) {
        this.store = store;
    }

    /**
     * 获取底层 Store 引用。
     */
    public RuntimeCartStore getStore() {
        return store;
    }

    // ==================== 购物车列表 ====================

    /**
     * 查询用户购物车列表（组装 sku 图片）。
     *
     * @param uid          用户 ID
     * @param masterSkus   SKU 主数据（用于图片补全）
     * @return 购物车 JSONArray
     */
    public JSONArray queryCartList(String uid, JSONObject masterSkus) {
        JSONArray result = new JSONArray();
        for (Map.Entry<String, JSONObject> entry : store.getAllCarts().entrySet()) {
            JSONObject cart = entry.getValue();
            if (!uid.equals(cart.getStr("userId", ""))) continue;

            String skuId = cart.getStr("skuId");
            JSONObject sku = masterSkus != null ? masterSkus.getJSONObject(skuId) : null;

            JSONObject item = new JSONObject();
            item.set("id", cart.getStr("id"));
            item.set("skuId", skuId);
            item.set("goodsId", cart.getStr("goodsId"));
            item.set("name", cart.getStr("name"));
            item.set("attrsText", cart.getStr("attrsText"));
            String cartPic = cart.getStr("picture", "");
            if (cartPic == null || cartPic.isBlank()) {
                cartPic = sku != null ? sku.getStr("picture", "") : "";
            }
            item.set("picture", MockImageUtils.normalizeImageUrl(cartPic, DEFAULT_FALLBACK_IMAGE));
            item.set("price", cart.getStr("price"));
            item.set("nowPrice", cart.getStr("nowPrice"));
            item.set("selected", cart.getBool("selected", true));
            item.set("stock", cart.getInt("stock", 100));
            item.set("count", cart.getInt("count", 1));
            item.set("isEffective", cart.getBool("isEffective", true));
            item.set("createTime", cart.getStr("createTime", ""));
            result.add(item);
        }
        return result;
    }

    // ==================== 添加购物车 ====================

    /**
     * 添加商品到购物车（去重累加）。
     *
     * @param uid            用户 ID
     * @param skuId          SKU ID
     * @param count          数量
     * @param masterSkus     SKU 主数据
     * @param masterProducts 商品 SPU 主数据
     * @return 错误消息，或 null 表示成功
     */
    public String addToCart(String uid, String skuId, int count,
                            JSONObject masterSkus, JSONObject masterProducts) {
        if (skuId == null || skuId.isBlank()) {
            return "SKU ID 不能为空";
        }

        // 检查是否已存在该 SKU → 累加数量
        Map.Entry<String, JSONObject> existing = store.findCartBySkuId(skuId, uid);
        if (existing != null) {
            JSONObject cart = existing.getValue();
            cart.set("count", cart.getInt("count", 0) + count);
            store.save();
            return null;
        }

        // 从 master 数据构建新条目
        JSONObject sku = masterSkus != null ? masterSkus.getJSONObject(skuId) : null;
        if (sku == null) {
            return "SKU 不存在";
        }

        String goodsId = sku.getStr("goodsId", "");
        JSONObject product = (goodsId.isEmpty() || masterProducts == null)
                ? null : masterProducts.getJSONObject(goodsId);

        String id = "cart_" + String.format("%03d", store.incrementAndGetCartId());
        JSONObject cartItem = new JSONObject();
        cartItem.set("id", id);
        cartItem.set("skuId", skuId);
        cartItem.set("userId", uid);
        cartItem.set("goodsId", goodsId);
        cartItem.set("name", product != null ? product.getStr("name", "") : "");
        cartItem.set("attrsText", sku.getStr("attrsText", ""));
        cartItem.set("picture", sku.getStr("picture", ""));
        cartItem.set("price", sku.getStr("price", "0"));
        cartItem.set("nowPrice", sku.getStr("price", "0"));
        cartItem.set("selected", true);
        cartItem.set("stock", sku.getInt("inventory", 100));
        cartItem.set("count", count);
        cartItem.set("isEffective", true);
        cartItem.set("createTime", LocalDateTime.now().toString());

        store.putCart(id, cartItem);
        store.save();
        return null;
    }

    // ==================== 删除购物车 ====================

    /**
     * 删除购物车条目。
     *
     * @param uid  用户 ID
     * @param ids  要删除的 ID 列表（支持 cart entry key 或 skuId）
     */
    public void deleteFromCart(String uid, List<?> ids) {
        store.removeCartsByUserAndIds(uid, ids);
        store.save();
    }

    // ==================== 更新购物车 ====================

    /**
     * 更新购物车条目（selected/count）。
     *
     * @param uid    用户 ID
     * @param skuId  SKU ID
     * @param params 更新参数（selected, count）
     * @return true 表示找到并更新，false 表示未找到
     */
    public boolean updateCart(String uid, String skuId, Map<String, Object> params) {
        Map.Entry<String, JSONObject> entry = store.findCartBySkuId(skuId, uid);
        if (entry == null) return false;

        JSONObject cart = entry.getValue();
        if (params.containsKey("selected")) {
            cart.set("selected", params.get("selected"));
        }
        if (params.containsKey("count")) {
            cart.set("count", params.get("count"));
        }
        store.save();
        return true;
    }

    // ==================== 合并购物车 ====================

    /**
     * 合并本地购物车到服务端。
     *
     * @param cartList       本地购物车列表
     * @param masterSkus     SKU 主数据
     * @param masterProducts 商品 SPU 主数据
     * @return 合并后的用户 ID
     */
    public String mergeCart(List<Map<String, Object>> cartList,
                            JSONObject masterSkus, JSONObject masterProducts) {
        // 从 body 提取 userId
        String userId = "";
        if (cartList != null && !cartList.isEmpty() && cartList.get(0).get("userId") != null) {
            userId = cartList.get(0).get("userId").toString();
        }
        final String uid = userId;

        if (cartList != null) {
            for (Map<String, Object> item : cartList) {
                Object skuIdObj = item.get("skuId");
                String skuId = skuIdObj == null ? null : skuIdObj.toString();
                if (skuId == null) continue;

                int count = item.containsKey("count") ? ((Number) item.get("count")).intValue() : 1;
                boolean selected = item.containsKey("selected") && Boolean.TRUE.equals(item.get("selected"));

                // 查找是否已存在
                Map.Entry<String, JSONObject> existing = store.findCartBySkuId(skuId, uid);
                if (existing != null) {
                    JSONObject cart = existing.getValue();
                    cart.set("count", cart.getInt("count", 0) + count);
                    if (item.containsKey("selected")) cart.set("selected", selected);
                } else {
                    // 构建新条目
                    JSONObject sku = masterSkus != null ? masterSkus.getJSONObject(skuId) : null;
                    if (sku == null) continue;
                    String goodsId = sku.getStr("goodsId", "");
                    JSONObject product = (goodsId.isEmpty() || masterProducts == null)
                            ? null : masterProducts.getJSONObject(goodsId);

                    String id = "cart_" + String.format("%03d", store.incrementAndGetCartId());
                    JSONObject newItem = new JSONObject();
                    newItem.set("id", id);
                    newItem.set("skuId", skuId);
                    newItem.set("userId", uid);
                    newItem.set("goodsId", goodsId);
                    newItem.set("name", product != null ? product.getStr("name", "") : "");
                    newItem.set("attrsText", sku.getStr("attrsText", ""));
                    newItem.set("picture", sku.getStr("picture", ""));
                    newItem.set("price", sku.getStr("price", "0"));
                    newItem.set("nowPrice", sku.getStr("price", "0"));
                    newItem.set("selected", selected);
                    newItem.set("stock", sku.getInt("inventory", 100));
                    newItem.set("count", count);
                    newItem.set("isEffective", true);
                    newItem.set("createTime", LocalDateTime.now().toString());
                    store.putCart(id, newItem);
                }
            }
        }
        store.save();
        return uid;
    }

    // ==================== 批量全选/取消全选 ====================

    /**
     * 批量设置购物车条目的选中状态。
     *
     * @param uid      用户 ID
     * @param selected 选中状态
     * @param ids      SKU ID 列表
     */
    public void setSelected(String uid, boolean selected, List<String> ids) {
        for (Map.Entry<String, JSONObject> entry : store.getAllCarts().entrySet()) {
            JSONObject cart = entry.getValue();
            if (!uid.equals(cart.getStr("userId", ""))) continue;
            if (ids.contains(cart.getStr("skuId"))) {
                cart.set("selected", selected);
            }
        }
        store.save();
    }

}
