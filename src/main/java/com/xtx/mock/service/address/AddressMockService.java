package com.xtx.mock.service.address;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeAddressStore;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * AddressMockService — 地址业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>地址列表查询（委托给 RuntimeAddressStore）</li>
 *   <li>地址新增/修改/删除</li>
 *   <li>默认地址管理</li>
 * </ul>
 * <p>
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockAddressController} 直接实例化。
 */
@Slf4j
public class AddressMockService {

    private final RuntimeAddressStore addressStore;

    public AddressMockService(RuntimeAddressStore addressStore) {
        this.addressStore = addressStore;
    }

    /**
     * 查询用户地址列表。
     */
    public JSONArray listByUserId(String uid) {
        return addressStore.listByUserId(uid);
    }

    /**
     * 根据 ID 获取地址。
     */
    public JSONObject getById(String id) {
        return addressStore.getById(id);
    }

    /**
     * 新增地址，返回完整地址对象。
     */
    public JSONObject add(Map<String, Object> params, String uid) {
        return addressStore.add(params, uid);
    }

    /**
     * 修改地址，返回修改后的地址对象。地址不存在或无权操作时返回 null。
     */
    public JSONObject update(String id, Map<String, Object> params, String uid) {
        return addressStore.update(id, params, uid);
    }

    /**
     * 删除地址。地址不存在或无权操作时返回 false。
     */
    public boolean delete(String id, String uid) {
        return addressStore.delete(id, uid);
    }

    /**
     * 重置为 seed 地址。
     */
    public void resetToSeed() {
        addressStore.resetToSeed();
    }

    /**
     * 返回地址数量。
     */
    public int count() {
        return addressStore.count();
    }

    /**
     * 初始化地址数据（从文件或 seed）。
     */
    public void init() {
        addressStore.init();
    }
}
