package com.xtx.mock.service.review;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xtx.mock.store.RuntimeReviewStore;
import com.xtx.mock.util.MockImageUtils;
import com.xtx.mock.util.MockProductViewUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReviewMockService — 评价模块业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>查询当前用户评价列表 + 候选项</li>
 *   <li>提交评价</li>
 *   <li>查询商品详情评价摘要 + 分页列表</li>
 *   <li>评价点赞/取消点赞</li>
 *   <li>校验订单归属、SKU 归属</li>
 *   <li>保持旧响应结构不变</li>
 * </ul>
 * <p>
 * 外部数据依赖（由调用方传入）：
 * <ul>
 *   <li>runtimeOrders — 订单数据（只读）</li>
 *   <li>runtimeUsers — 用户数据（只读，用于获取头像和昵称）</li>
 *   <li>masterSkus — SKU 主数据（只读）</li>
 *   <li>masterProducts — 商品主数据（只读）</li>
 *   <li>masterEvaluations — 预置评价数据（只读）</li>
 *   <li>featureCounter — ID 生成器</li>
 * </ul>
 */
@Slf4j
public class ReviewMockService {

    private final RuntimeReviewStore store;

    public ReviewMockService(RuntimeReviewStore store) {
        this.store = store;
    }

    // ==================== 辅助方法 ====================


    /** 判断订单是否属于指定用户 */
    private boolean isOrderOwner(JSONObject order, String uid) {
        return order != null && uid.equals(order.getStr("userId", ""));
    }

    // ==================== 构建商品名/图片解析 ====================

    /**
     * 解析商品名称：从订单 SKU → master product → master SKU 逐级回退。
     */
    private String resolveProductName(JSONObject orderSku, String skuId,
                                       JSONObject masterProducts, JSONObject masterSkus) {
        String name = orderSku.getStr("name");
        if (name != null && !name.isBlank()) return name;

        String gid = orderSku.getStr("goodsId", "");
        if (gid.isBlank()) gid = orderSku.getStr("spuId", "");
        JSONObject mProd = gid.isEmpty() ? null : (masterProducts != null ? masterProducts.getJSONObject(gid) : null);
        if (mProd != null) {
            name = mProd.getStr("name", "");
            if (!name.isBlank()) return name;
        }

        if (skuId != null && masterSkus != null) {
            JSONObject mSku = masterSkus.getJSONObject(skuId);
            if (mSku != null) {
                name = mSku.getStr("name", "");
                if (!name.isBlank()) return name;
                String prodId = mSku.getStr("goodsId", "");
                JSONObject prod = masterProducts != null ? masterProducts.getJSONObject(prodId) : null;
                if (prod != null) {
                    name = prod.getStr("name", "");
                    if (!name.isBlank()) return name;
                }
            }
        }
        return "";
    }

    /**
     * 解析商品图片：从订单 SKU → master SKU → master product 逐级回退。
     */
    private String resolveProductImage(JSONObject orderSku, String skuId,
                                        JSONObject masterSkus, JSONObject masterProducts) {
        String img = orderSku.getStr("image");
        if (img == null || img.isBlank()) img = orderSku.getStr("picture");
        if (img == null || img.isBlank() && skuId != null && masterSkus != null) {
            JSONObject mSku = masterSkus.getJSONObject(skuId);
            if (mSku != null) img = mSku.getStr("picture", "");
        }
        if (img == null || img.isBlank() && masterProducts != null) {
            String gid = orderSku.getStr("goodsId", "");
            if (gid.isBlank()) gid = orderSku.getStr("spuId", "");
            JSONObject mProd = masterProducts.getJSONObject(gid);
            if (mProd != null) {
                img = mProd.getStr("picture", "");
                JSONArray mainPics = mProd.getJSONArray("mainPictures");
                if (mainPics != null && mainPics.size() > 0) img = mainPics.getStr(0);
            }
        }
        return img;
    }

    /**
     * 解析 goodsId：从订单 SKU → spuId 逐级回退。
     */
    private String resolveGoodsId(JSONObject orderSku, String fallbackGoodsId) {
        String gid = orderSku.getStr("goodsId");
        if (gid == null || gid.isBlank()) gid = orderSku.getStr("spuId", "");
        if (gid == null || gid.isBlank()) gid = fallbackGoodsId;
        return gid;
    }

    // ==================== 构建默认种子评价 ====================

    /**
     * 为指定用户构建默认评价数据（基于该用户的订单 SKU）。
     * 最多 6 条，前 2 条为 pending（待评价），其余为 completed（已评价）。
     *
     * @param userId         用户 ID
     * @param runtimeOrders  所有订单数据（只读）
     * @param masterSkus     SKU 主数据（只读）
     * @param masterProducts 商品主数据（只读）
     * @param featureCounter ID 生成器
     */
    public void seedDefaultReviews(String userId,
                                    Map<String, JSONObject> runtimeOrders,
                                    JSONObject masterSkus,
                                    JSONObject masterProducts,
                                    AtomicInteger featureCounter) {
        JSONArray existing = store.getUserReviewsRaw(userId);
        if (existing != null && existing.size() > 0) return;

        JSONArray arr = new JSONArray();
        for (JSONObject order : runtimeOrders.values()) {
            if (!isOrderOwner(order, userId)) continue;
            JSONArray skus = order.getJSONArray("skus");
            if (skus == null) continue;
            for (int i = 0; i < skus.size(); i++) {
                JSONObject sku = skus.getJSONObject(i);
                boolean pending = i < 2;

                // 确保图片有效：优先订单快照，其次反查 masterSkus，最后反查 masterProducts
                String reviewImage = resolveProductImage(sku, sku.getStr("skuId"), masterSkus, masterProducts);

                String skuGoodsId = resolveGoodsId(sku, "");
                String defName = resolveProductName(sku, sku.getStr("skuId"), masterProducts, masterSkus);

                String normalizedPic = MockImageUtils.normalizeImageUrl(reviewImage, MockImageUtils.DEFAULT_FALLBACK_IMAGE);
                JSONObject item = new JSONObject()
                        .set("id", "review_" + featureCounter.incrementAndGet())
                        .set("orderId", order.getStr("id"))
                        .set("skuId", sku.getStr("skuId"))
                        .set("spuId", sku.getStr("spuId"))
                        .set("goodsId", skuGoodsId)
                        .set("name", defName)
                        .set("title", defName)
                        .set("goodsName", defName)
                        .set("image", normalizedPic)
                        .set("picture", normalizedPic)
                        .set("attrsText", sku.getStr("attrsText"))
                        .set("reviewerName", "")
                        .set("status", pending ? "pending" : "completed")
                        .set("score", pending ? 0 : 5)
                        .set("content", pending ? "" : "包装完整，发货及时，整体体验不错。")
                        .set("anonymous", false)
                        .set("createdAt", pending ? "" : "2026-06-08 18:30:00");
                arr.add(item);
                if (arr.size() >= 6) break;
            }
            if (arr.size() >= 6) break;
        }
        // 将构建好的评价逐个添加到 store
        for (Object obj : arr) {
            JSONArray userReviews = store.getUserReviews(userId);
            userReviews.add((JSONObject) obj);
        }
        log.info("Seeded {} default reviews for user {}", arr.size(), userId);
    }

    // ==================== GET /member/review ====================

    /**
     * 查询当前用户的评价列表及候选项。
     *
     * @param uid            当前用户 ID
     * @param orderId        可选的订单 ID（返回该订单未评价 SKU 候选项）
     * @param runtimeOrders  所有订单数据（只读）
     * @param masterSkus     SKU 主数据（只读）
     * @param masterProducts 商品主数据（只读）
     * @param featureCounter ID 生成器
     * @return JSONObject 包含 items（评价列表）和 stats（统计）
     */
    public JSONObject queryMemberReviews(String uid, String orderId,
                                          Map<String, JSONObject> runtimeOrders,
                                          JSONObject masterSkus,
                                          JSONObject masterProducts,
                                          AtomicInteger featureCounter) {
        JSONArray items = store.getUserReviews(uid);

        if (orderId != null && !orderId.isBlank()) {
            JSONObject order = runtimeOrders.get(orderId);
            if (isOrderOwner(order, uid)) {
                JSONArray skus = order.getJSONArray("skus");
                if (skus != null) {
                    for (Object obj : skus) {
                        JSONObject sku = (JSONObject) obj;
                        String finalOrderId = orderId;
                        String finalSkuId = sku.getStr("skuId");
                        boolean exists = items.stream()
                                .map(it -> (JSONObject) it)
                                .anyMatch(it -> finalOrderId.equals(it.getStr("orderId"))
                                        && finalSkuId.equals(it.getStr("skuId")));
                        if (!exists) {
                            String reviewImg = resolveProductImage(sku, finalSkuId, masterSkus, masterProducts);
                            String dynName = resolveProductName(sku, finalSkuId, masterProducts, masterSkus);
                            String dynGoodsId = resolveGoodsId(sku, "");
                            String dynPic = MockImageUtils.normalizeImageUrl(reviewImg, MockImageUtils.DEFAULT_FALLBACK_IMAGE);
                            items.add(0, new JSONObject()
                                    .set("id", "review_" + featureCounter.incrementAndGet())
                                    .set("orderId", orderId)
                                    .set("skuId", finalSkuId)
                                    .set("spuId", sku.getStr("spuId"))
                                    .set("goodsId", dynGoodsId)
                                    .set("name", dynName)
                                    .set("image", dynPic)
                                    .set("picture", dynPic)
                                    .set("attrsText", sku.getStr("attrsText"))
                                    .set("reviewerName", "")
                                    .set("status", "pending")
                                    .set("score", 0).set("content", "").set("anonymous", false).set("createdAt", ""));
                        }
                    }
                }
            }
        }

        JSONObject result = new JSONObject();
        result.set("items", items);
        JSONObject stats = new JSONObject();
        stats.set("pending", items.stream().map(o -> (JSONObject) o).filter(item -> "pending".equals(item.getStr("status"))).count());
        stats.set("completed", items.stream().map(o -> (JSONObject) o).filter(item -> "completed".equals(item.getStr("status"))).count());
        result.set("stats", stats);
        return result;
    }

    // ==================== POST /member/review ====================

    /**
     * 提交评价。
     *
     * @param uid            当前用户 ID
     * @param params         请求参数（id, score, content, anonymous）
     * @param runtimeOrders  所有订单数据（读写，用于更新订单状态）
     * @param runtimeUsers   用户数据（只读，用于获取头像和昵称）
     * @param masterSkus     SKU 主数据（只读）
     * @param masterProducts 商品主数据（只读）
     * @param featureCounter ID 生成器
     * @return JSONObject 更新后的评价项（含完整商品信息快照）
     */
    public JSONObject submitMemberReview(String uid, Map<String, Object> params,
                                          Map<String, JSONObject> runtimeOrders,
                                          Map<String, JSONObject> runtimeUsers,
                                          JSONObject masterSkus,
                                          JSONObject masterProducts,
                                          AtomicInteger featureCounter) {
        String reviewId = params.get("id") != null ? params.get("id").toString() : "";
        JSONArray items = store.getUserReviews(uid);

        JSONObject updatedItem = null;
        for (Object obj : items) {
            JSONObject item = (JSONObject) obj;
            if (!item.getStr("id").equals(reviewId)) continue;
            updatedItem = item;
            break;
        }
        if (updatedItem == null) {
            // 如果指定 ID 不存在，不处理（旧逻辑中通过遍历定位，找不到则无操作）
            JSONObject result = new JSONObject();
            result.set("items", items);
            return result;
        }

        updatedItem.set("status", "completed");
        updatedItem.set("score", params.get("score"));
        updatedItem.set("content", params.get("content"));
        updatedItem.set("anonymous", Boolean.TRUE.equals(params.get("anonymous")));
        updatedItem.set("createdAt", java.time.LocalDateTime.now().toString().replace('T', ' ').substring(0, 19));

        // UNCONDITIONALLY set complete product snapshot from order SKU + master data fallback
        String revOrderId = updatedItem.getStr("orderId");
        String revSkuId = updatedItem.getStr("skuId");
        String revGoodsId = updatedItem.getStr("goodsId", "");

        if (revOrderId != null && !revOrderId.isBlank() && revSkuId != null && !revSkuId.isBlank()) {
            JSONObject revOrder = runtimeOrders.get(revOrderId);
            if (revOrder != null) {
                JSONArray revSkus = revOrder.getJSONArray("skus");
                if (revSkus != null) {
                    for (Object rso : revSkus) {
                        JSONObject rs = (JSONObject) rso;
                        if (revSkuId.equals(rs.getStr("skuId"))) {
                            String productName = resolveProductName(rs, revSkuId, masterProducts, masterSkus);
                            String productImage = resolveProductImage(rs, revSkuId, masterSkus, masterProducts);
                            String prodAttrs = rs.getStr("attrsText");
                            if (prodAttrs == null || prodAttrs.isBlank()) {
                                JSONObject mSku = masterSkus != null ? masterSkus.getJSONObject(revSkuId) : null;
                                if (mSku != null) prodAttrs = MockProductViewUtils.formatSpecsText(mSku.get("specs"));
                            }
                            String prodGoodsId = resolveGoodsId(rs, revGoodsId);

                            String finalName = productName != null ? productName : "";
                            updatedItem.set("name", finalName);
                            updatedItem.set("title", finalName);
                            updatedItem.set("goodsName", finalName);

                            String finalImg = MockImageUtils.normalizeImageUrl(productImage, MockImageUtils.DEFAULT_FALLBACK_IMAGE);
                            updatedItem.set("image", finalImg);
                            updatedItem.set("picture", finalImg);

                            updatedItem.set("attrsText", prodAttrs != null ? prodAttrs : "");
                            updatedItem.set("goodsId", prodGoodsId);
                            break;
                        }
                    }
                }
            }
        }

        // Ensure name/title/goodsName are non-blank even without order lookup
        if (updatedItem.getStr("name") == null || updatedItem.getStr("name").isBlank()) {
            String fallbackName = "";
            JSONObject mSku = masterSkus != null ? masterSkus.getJSONObject(revSkuId) : null;
            if (mSku != null) {
                fallbackName = mSku.getStr("name", "");
                if (fallbackName.isBlank()) {
                    JSONObject mProd = masterProducts != null
                            ? masterProducts.getJSONObject(mSku.getStr("goodsId", ""))
                            : null;
                    if (mProd != null) fallbackName = mProd.getStr("name", "");
                }
            }
            String fb = fallbackName;
            updatedItem.set("name", fb);
            updatedItem.set("title", fb);
            updatedItem.set("goodsName", fb);
        }

        // 检查该订单下所有 SKU 是否都已评价 → 订单状态变为已完成(5)
        String orderId = updatedItem.getStr("orderId");
        if (orderId != null && !orderId.isBlank()) {
            JSONObject order = runtimeOrders.get(orderId);
            if (order != null && isOrderOwner(order, uid) && order.getInt("orderState", 0) == 4) {
                boolean allCompleted = true;
                JSONArray orderSkus = order.getJSONArray("skus");
                if (orderSkus != null) {
                    for (Object skuObj : orderSkus) {
                        JSONObject orderSku = (JSONObject) skuObj;
                        String skuId = orderSku.getStr("skuId");
                        boolean skuReviewed = false;
                        for (Object revObj : items) {
                            JSONObject revItem = (JSONObject) revObj;
                            if (orderId.equals(revItem.getStr("orderId"))
                                    && skuId.equals(revItem.getStr("skuId"))
                                    && "completed".equals(revItem.getStr("status"))) {
                                skuReviewed = true;
                                break;
                            }
                        }
                        if (!skuReviewed) {
                            allCompleted = false;
                            break;
                        }
                    }
                }
                if (allCompleted) {
                    order.set("orderState", 5);
                    order.set("endTime", java.time.LocalDateTime.now().toString());
                    runtimeOrders.put(orderId, order);
                }
            }
        }

        // ===== 同步到商品详情评价 (runtimeGoodsReviews) =====
        String reviewedGoodsId = updatedItem.getStr("goodsId");
        if (reviewedGoodsId != null && !reviewedGoodsId.isBlank()) {
            JSONArray goodsReviews = store.getOrCreateGoodsReviews(reviewedGoodsId);

            // 检查是否已存在该评价（防重复提交同一 orderId+skuId）
            boolean exists = false;
            for (Object gro : goodsReviews) {
                JSONObject gr = (JSONObject) gro;
                if (orderId.equals(gr.getStr("orderId")) && revSkuId.equals(gr.getStr("skuId"))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                // 获取用户头像和昵称
                JSONObject reviewerUser = runtimeUsers != null ? runtimeUsers.get(uid) : null;
                String reviewerAvatar = reviewerUser != null ? reviewerUser.getStr("avatar", "") : "";
                String reviewerNickname = reviewerUser != null ? reviewerUser.getStr("nickname", uid) : uid;
                if (reviewerAvatar == null || reviewerAvatar.isBlank()) {
                    reviewerAvatar = "https://picsum.photos/seed/" + uid + "/100/100";
                }

                // 构建 orderInfo.specs
                JSONArray reviewSpecs = new JSONArray();
                JSONObject mSkuForSpecs = masterSkus != null ? masterSkus.getJSONObject(revSkuId) : null;
                if (mSkuForSpecs != null) {
                    JSONArray masterSpecs = mSkuForSpecs.getJSONArray("specs");
                    if (masterSpecs != null) {
                        for (Object specObj : masterSpecs) {
                            JSONObject spec = (JSONObject) specObj;
                            reviewSpecs.add(new JSONObject()
                                    .set("name", spec.getStr("name", ""))
                                    .set("nameValue", spec.getStr("valueName", spec.getStr("nameValue", ""))));
                        }
                    }
                }
                // 如果 master SKU 无规格信息，尝试从 attrsText 构造
                if (reviewSpecs.isEmpty()) {
                    String attrsText = updatedItem.getStr("attrsText", "");
                    if (attrsText != null && !attrsText.isBlank() && attrsText.contains("：")) {
                        String[] parts = attrsText.split("[：:]", 2);
                        if (parts.length == 2) {
                            reviewSpecs.add(new JSONObject()
                                    .set("name", parts[0].trim())
                                    .set("nameValue", parts[1].trim()));
                        }
                    }
                }

                JSONObject goodsReviewEntry = new JSONObject()
                        .set("id", updatedItem.getStr("id"))
                        .set("orderId", orderId)
                        .set("skuId", revSkuId)
                        .set("goodsId", reviewedGoodsId)
                        .set("spuId", updatedItem.getStr("spuId", ""))
                        .set("name", updatedItem.getStr("name", ""))
                        .set("picture", updatedItem.getStr("picture", ""))
                        .set("attrsText", updatedItem.getStr("attrsText", ""))
                        .set("content", updatedItem.getStr("content", ""))
                        .set("score", updatedItem.get("score"))
                        .set("anonymous", updatedItem.getBool("anonymous", false))
                        .set("reviewerName", updatedItem.getStr("reviewerName", ""))
                        .set("createTime", updatedItem.getStr("createdAt", ""))
                        .set("praiseCount", 0)
                        .set("isPraise", false)
                        .set("tag", "用户评价")
                        .set("pictures", new JSONArray())
                        .set("orderInfo", new JSONObject()
                                .set("specs", reviewSpecs)
                                .set("picture", updatedItem.getStr("picture", ""))
                                .set("goodsName", updatedItem.getStr("name", "")))
                        .set("member", new JSONObject()
                                .set("avatar", reviewerAvatar)
                                .set("nickname", reviewerNickname));
                goodsReviews.add(goodsReviewEntry);
            }
        }

        // 持久化商品详情评价
        store.save();

        log.info("Review submitted: user={} reviewId={} orderId={} skuId={}",
                uid, reviewId, orderId, revSkuId);
        return updatedItem;
    }

    // ==================== GET /goods/{id}/evaluate ====================

    /**
     * 查询商品详情评价摘要（统计信息）。
     *
     * @param goodsId          商品 ID
     * @param masterEvaluations 预置评价数据（只读）
     * @return JSONObject 包含 salesCount, praisePercent, evaluateCount, hasPictureCount, tags
     */
    public JSONObject queryGoodsEvaluate(String goodsId, JSONObject masterEvaluations) {
        JSONObject ev = masterEvaluations != null ? masterEvaluations.getJSONObject(goodsId) : null;

        // 合并 runtimeGoodsReviews 提交的用户评价
        int runtimeCount = 0;
        JSONArray runtimeItems = store.getGoodsReviews(goodsId);
        if (runtimeItems != null) runtimeCount = runtimeItems.size();

        if (ev == null && runtimeCount == 0) {
            JSONObject result = new JSONObject();
            result.set("salesCount", 0);
            result.set("praisePercent", "0%");
            result.set("evaluateCount", 0);
            result.set("hasPictureCount", 0);
            result.set("tags", new JSONArray());
            return result;
        }

        JSONObject result = new JSONObject();
        if (ev != null) {
            result.set("salesCount", ev.get("salesCount"));
            result.set("praisePercent", ev.get("praisePercent"));
            result.set("evaluateCount", ev.getInt("evaluateCount", 0) + runtimeCount);
            result.set("hasPictureCount", ev.get("hasPictureCount"));
            result.set("tags", ev.get("tags"));
        } else {
            result.set("salesCount", 0);
            result.set("praisePercent", "100%");
            result.set("evaluateCount", runtimeCount);
            result.set("hasPictureCount", 0);
            result.set("tags", new JSONArray());
        }
        return result;
    }

    // ==================== GET /goods/{id}/evaluate/page ====================

    /**
     * 查询商品详情评价分页列表。
     *
     * @param goodsId          商品 ID
     * @param page             页码
     * @param pageSize         每页条数
     * @param hasPicture       仅显示有图评价
     * @param tag              按标签筛选
     * @param sortField        排序字段
     * @param masterEvaluations 预置评价数据（只读）
     * @return JSONObject 分页结果
     */
    public JSONObject queryGoodsEvaluatePage(String goodsId, int page, int pageSize,
                                              String hasPicture, String tag, String sortField,
                                              JSONObject masterEvaluations) {
        // 收集所有评价: master 预置评价 + runtimeGoodsReviews 用户提交评价
        List<JSONObject> allItems = new ArrayList<>();

        // 1. Master 预置评价
        JSONObject ev = masterEvaluations != null ? masterEvaluations.getJSONObject(goodsId) : null;
        if (ev != null) {
            JSONObject masterPage = (JSONObject) ev.get("page");
            if (masterPage != null) {
                JSONArray masterItems = masterPage.getJSONArray("items");
                if (masterItems != null) {
                    for (int i = 0; i < masterItems.size(); i++) {
                        allItems.add(masterItems.getJSONObject(i));
                    }
                }
            }
        }

        // 2. Runtime 用户提交评价
        JSONArray runtimeItems = store.getGoodsReviews(goodsId);
        if (runtimeItems != null) {
            for (int i = 0; i < runtimeItems.size(); i++) {
                allItems.add(runtimeItems.getJSONObject(i));
            }
        }

        if (allItems.isEmpty()) {
            JSONObject emptyPage = new JSONObject();
            emptyPage.set("counts", 0);
            emptyPage.set("pageSize", pageSize);
            emptyPage.set("pages", 0);
            emptyPage.set("page", page);
            emptyPage.set("items", new JSONArray());
            return emptyPage;
        }

        // Step 1: Filter items + prepare runtime fields
        List<JSONObject> filtered = new ArrayList<>();
        for (JSONObject item : allItems) {
            // Seed praise count for master items
            String evalId = item.getStr("id");
            if (evalId != null) {
                store.ensureBasePraiseCount(evalId, masterEvaluations);
            }

            // Compute current praiseCount = base + active likes count
            if (evalId != null) {
                int activeLikes = store.countActiveLikes(evalId);
                int baseCount = store.getBasePraiseCount(evalId);
                item.set("praiseCount", baseCount + activeLikes);

                // Compute isPraise for the current user (default user)
                boolean isPraised = store.isPraised("xiaotuxian001", evalId);
                item.set("isPraise", isPraised);
            }

            // hasPicture filter
            if ("true".equals(hasPicture) || "1".equals(hasPicture)) {
                JSONArray pics = item.getJSONArray("pictures");
                if (pics == null || pics.isEmpty()) continue;
            }

            // tag filter
            if (tag != null && !tag.isBlank()) {
                String itemTag = item.getStr("tag");
                if (!tag.equals(itemTag)) continue;
            }

            filtered.add(item);
        }

        // Step 2: Sort
        if ("createTime".equals(sortField)) {
            filtered.sort((a, b) -> b.getStr("createTime", "").compareTo(a.getStr("createTime", "")));
        } else if ("praiseCount".equals(sortField)) {
            filtered.sort((a, b) -> Integer.compare(
                    b.getInt("praiseCount", 0), a.getInt("praiseCount", 0)));
        } else {
            filtered.sort((a, b) -> b.getStr("createTime", "").compareTo(a.getStr("createTime", "")));
        }

        // Step 3: Paginate
        int counts = filtered.size();
        int pages = Math.max(1, (int) Math.ceil((double) counts / pageSize));
        int fromIndex = (page - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, counts);

        JSONArray pageItems = new JSONArray();
        if (fromIndex < counts) {
            for (int i = fromIndex; i < toIndex; i++) {
                pageItems.add(filtered.get(i));
            }
        }

        JSONObject result = new JSONObject();
        result.set("counts", counts);
        result.set("pageSize", pageSize);
        result.set("pages", pages);
        result.set("page", page);
        result.set("items", pageItems);
        return result;
    }

    // ==================== PUT/POST /goods/evaluate/{evaluateId}/praise ====================

    /**
     * 点赞/取消点赞评价（幂等）。
     *
     * @param evaluateId 评价 ID
     * @param body       请求体（可选），{ "isPraise": true/false }
     * @return JSONObject { evaluateId, isPraise, praiseCount }
     */
    public JSONObject togglePraise(String evaluateId, JSONObject body) {
        // Determine desired state
        boolean wantPraise = true;
        if (body != null && body.containsKey("isPraise")) {
            wantPraise = body.getBool("isPraise", true);
        } else {
            // Toggle mode: if already praised, want to cancel
            wantPraise = !store.isPraised("xiaotuxian001", evaluateId);
        }

        // Ensure base count is seeded
        store.ensureBasePraiseCount(evaluateId, new JSONObject()); // empty masterEvaluations; base may not seed from here

        boolean wasPraised = store.isPraised("xiaotuxian001", evaluateId);

        if (wantPraise && !wasPraised) {
            store.setPraised("xiaotuxian001", evaluateId, true);
        } else if (!wantPraise && wasPraised) {
            store.setPraised("xiaotuxian001", evaluateId, false);
        }

        boolean nowPraised = store.isPraised("xiaotuxian001", evaluateId);
        int baseCount = store.getBasePraiseCount(evaluateId);
        int activeLikes = store.countActiveLikes(evaluateId);

        JSONObject result = new JSONObject();
        result.set("evaluateId", evaluateId);
        result.set("isPraise", nowPraised);
        result.set("praiseCount", baseCount + activeLikes);
        return result;
    }

    /**
     * 点赞/取消点赞评价（带 masterEvaluations 用于 seed base count）。
     */
    public JSONObject togglePraise(String evaluateId, JSONObject body, JSONObject masterEvaluations) {
        // Ensure base count is seeded
        store.ensureBasePraiseCount(evaluateId, masterEvaluations);

        return togglePraise(evaluateId, body);
    }
}
