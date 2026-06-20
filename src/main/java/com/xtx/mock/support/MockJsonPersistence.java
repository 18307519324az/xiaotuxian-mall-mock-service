package com.xtx.mock.support;

import cn.hutool.core.io.file.FileReader;
import cn.hutool.core.io.file.FileWriter;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;

/**
 * MockJsonPersistence — 统一 JSON 文件持久化工具。
 *
 * 职责：
 * 1. 统一 JSON 文件加载（load）；
 * 2. 统一 JSON 文件保存（save），含 .tmp → rename 原子写；
 * 3. 避免各 Store 复制重复的 save/load 代码；
 * 4. 支持 JSONObject 和 JSONArray 两种数据格式。
 *
 * 原子写策略：
 *   save() 先将数据写入 .tmp 临时文件，然后 rename 为目标文件，
 *   防止写中断导致数据损坏。
 *
 * 使用方式：
 * <pre>
 *   // 保存
 *   MockJsonPersistence.save("/data/user-orders-runtime.json", dataObj);
 *
 *   // 加载
 *   JSONObject obj = MockJsonPersistence.loadObject("/data/user-orders-runtime.json");
 *   JSONArray arr = MockJsonPersistence.loadArray("/data/user-something.json");
 * </pre>
 */
@Slf4j
public class MockJsonPersistence {

    private static final String PROJECT_ROOT;

    static {
        PROJECT_ROOT = System.getProperty("user.dir", ".");
    }

    private MockJsonPersistence() {
        // utility class
    }

    /**
     * Saves a JSONObject to the specified relative path using atomic write.
     *
     * @param relativePath  relative path under project root (e.g. "/data/user-orders-runtime.json")
     * @param data          the data to persist
     */
    public static void save(String relativePath, JSONObject data) {
        save(relativePath, data, 2);
    }

    /**
     * Saves a JSONArray to the specified relative path using atomic write.
     *
     * @param relativePath  relative path under project root
     * @param data          the data to persist
     */
    public static void save(String relativePath, JSONArray data) {
        save(relativePath, data, 2);
    }

    /**
     * Saves a JSONObject with custom indent factor.
     */
    public static void save(String relativePath, Object data, int indentFactor) {
        String absPath = PROJECT_ROOT + relativePath;
        String tmpPath = absPath + ".tmp";
        try {
            String json;
            if (data instanceof JSONObject) {
                json = ((JSONObject) data).toJSONString(indentFactor);
            } else if (data instanceof JSONArray) {
                json = ((JSONArray) data).toJSONString(indentFactor);
            } else {
                json = String.valueOf(data);
            }
            // Atomic write: write to .tmp first, then rename
            FileWriter writer = new FileWriter(tmpPath);
            writer.write(json);
            File tmpFile = new File(tmpPath);
            File targetFile = new File(absPath);
            if (targetFile.exists()) {
                if (!targetFile.delete()) {
                    log.warn("Failed to delete existing file: {}", absPath);
                }
            }
            if (!tmpFile.renameTo(targetFile)) {
                log.warn("Failed to rename .tmp to target: {}", absPath);
            }
        } catch (Exception e) {
            log.error("Failed to save JSON to {}: {}", relativePath, e.getMessage(), e);
        }
    }

    /**
     * Loads a JSONObject from the specified relative path.
     *
     * @param relativePath  relative path under project root
     * @return the loaded JSONObject, or null if file does not exist or is empty
     */
    public static JSONObject loadObject(String relativePath) {
        return loadObject(relativePath, null);
    }

    /**
     * Loads a JSONObject from the specified relative path with default return.
     */
    public static JSONObject loadObject(String relativePath, JSONObject defaultValue) {
        String absPath = PROJECT_ROOT + relativePath;
        File f = new File(absPath);
        if (!f.exists() || f.length() == 0) {
            return defaultValue;
        }
        try {
            String content = FileReader.create(f).readString();
            return JSONUtil.parseObj(content);
        } catch (Exception e) {
            log.error("Failed to load JSONObject from {}: {}", relativePath, e.getMessage(), e);
            return defaultValue;
        }
    }

    /**
     * Loads a JSONArray from the specified relative path.
     *
     * @param relativePath  relative path under project root
     * @return the loaded JSONArray, or null if file does not exist or is empty
     */
    public static JSONArray loadArray(String relativePath) {
        return loadArray(relativePath, null);
    }

    /**
     * Loads a JSONArray from the specified relative path with default return.
     */
    public static JSONArray loadArray(String relativePath, JSONArray defaultValue) {
        String absPath = PROJECT_ROOT + relativePath;
        File f = new File(absPath);
        if (!f.exists() || f.length() == 0) {
            return defaultValue;
        }
        try {
            String content = FileReader.create(f).readString();
            return JSONUtil.parseArray(content);
        } catch (Exception e) {
            log.error("Failed to load JSONArray from {}: {}", relativePath, e.getMessage(), e);
            return defaultValue;
        }
    }

    /**
     * Checks if a data file exists.
     */
    public static boolean exists(String relativePath) {
        return new File(PROJECT_ROOT + relativePath).exists();
    }

    /**
     * Returns the absolute path for a relative path.
     */
    public static String absPath(String relativePath) {
        return PROJECT_ROOT + relativePath;
    }
}
