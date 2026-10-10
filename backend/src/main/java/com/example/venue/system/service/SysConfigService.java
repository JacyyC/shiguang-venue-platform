package com.example.venue.system.service;

import com.example.venue.system.entity.SysConfig;
import com.example.venue.system.mapper.SysConfigMapper;
import org.springframework.stereotype.Service;

/**
 * 系统配置读取服务，提供类型安全的配置访问方法。
 * <p>
 * 替代各服务中重复的 configInt/configBoolean 私有方法。
 */
@Service
public class SysConfigService {
    private final SysConfigMapper configMapper;

    public SysConfigService(SysConfigMapper configMapper) {
        this.configMapper = configMapper;
    }

    /**
     * 读取整型配置，如果配置不存在或格式非法则返回默认值。
     */
    public int getInt(String key, int fallback) {
        SysConfig config = configMapper.findByKey(key);
        if (config == null || config.configValue == null || config.configValue.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(config.configValue.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * 读取布尔配置，如果配置不存在则返回默认值。
     * 支持常见的布尔字符串：true/false、yes/no、1/0。
     */
    public boolean getBoolean(String key, boolean fallback) {
        SysConfig config = configMapper.findByKey(key);
        if (config == null || config.configValue == null || config.configValue.isBlank()) {
            return fallback;
        }
        String value = config.configValue.trim().toLowerCase();
        if ("true".equals(value) || "yes".equals(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equals(value) || "no".equals(value) || "0".equals(value)) {
            return false;
        }
        return fallback;
    }

    /**
     * 读取字符串配置，如果配置不存在则返回默认值。
     */
    public String getString(String key, String fallback) {
        SysConfig config = configMapper.findByKey(key);
        return config == null || config.configValue == null ? fallback : config.configValue;
    }
}
