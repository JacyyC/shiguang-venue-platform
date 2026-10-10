package com.example.venue.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.provider.entity.ProviderRegistryEntry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProviderRegistryEntryMapper extends BaseMapper<ProviderRegistryEntry> {
    @Select("""
        SELECT id, provider_key, provider_domain, provider_env, provider_name, display_name,
               config_version, enabled, status, freshness_seconds, failure_threshold, timeout_ms,
               last_success_at, last_failure_at, consecutive_failures, last_failure_code,
               last_failure_message, metadata_json, create_time, update_time, deleted
        FROM provider_registry_entry
        WHERE provider_key = #{providerKey} AND deleted = 0
        LIMIT 1 FOR UPDATE
        """)
    ProviderRegistryEntry selectByProviderKeyForUpdate(@Param("providerKey") String providerKey);

    @Select("""
        SELECT id, provider_key, provider_domain, provider_env, provider_name, display_name,
               config_version, enabled, status, freshness_seconds, failure_threshold, timeout_ms,
               last_success_at, last_failure_at, consecutive_failures, last_failure_code,
               last_failure_message, metadata_json, create_time, update_time, deleted
        FROM provider_registry_entry
        WHERE provider_key = #{providerKey}
        LIMIT 1
        """)
    ProviderRegistryEntry selectByProviderKeyIncludingDeleted(@Param("providerKey") String providerKey);

    @Select("""
        SELECT id, provider_key, provider_domain, provider_env, provider_name, display_name,
               config_version, enabled, status, freshness_seconds, failure_threshold, timeout_ms,
               last_success_at, last_failure_at, consecutive_failures, last_failure_code,
               last_failure_message, metadata_json, create_time, update_time, deleted
        FROM provider_registry_entry
        WHERE provider_key = #{providerKey}
        LIMIT 1 FOR UPDATE
        """)
    ProviderRegistryEntry selectByProviderKeyIncludingDeletedForUpdate(@Param("providerKey") String providerKey);

    @Insert("""
        INSERT INTO provider_registry_entry
          (provider_key, provider_domain, provider_env, provider_name, display_name, config_version,
           enabled, status, consecutive_failures, metadata_json, create_time, update_time, deleted)
        VALUES
          (#{entry.providerKey}, #{entry.providerDomain}, #{entry.providerEnv}, #{entry.providerName},
           #{entry.displayName}, #{entry.configVersion}, #{entry.enabled}, #{entry.status},
           #{entry.consecutiveFailures}, #{entry.metadataJson,jdbcType=VARCHAR}, #{entry.createTime},
           #{entry.updateTime}, #{entry.deleted})
        ON DUPLICATE KEY UPDATE
          provider_domain = VALUES(provider_domain),
          provider_env = VALUES(provider_env),
          provider_name = VALUES(provider_name),
          display_name = VALUES(display_name),
          config_version = VALUES(config_version),
          enabled = VALUES(enabled),
          status = CASE
              WHEN deleted = 1 THEN VALUES(status)
              WHEN VALUES(enabled) = 0 THEN 'DISABLED'
              WHEN status = 'DISABLED' THEN 'ACTIVE'
              ELSE status
          END,
          metadata_json = VALUES(metadata_json),
          update_time = VALUES(update_time),
          deleted = 0
        """)
    int upsertDescriptor(@Param("entry") ProviderRegistryEntry entry);

    @Update("""
        UPDATE provider_registry_entry
        SET provider_domain = #{entry.providerDomain},
            provider_env = #{entry.providerEnv},
            provider_name = #{entry.providerName},
            display_name = #{entry.displayName},
            config_version = #{entry.configVersion},
            enabled = #{entry.enabled},
            status = #{entry.status},
            metadata_json = #{entry.metadataJson,jdbcType=VARCHAR},
            update_time = #{entry.updateTime},
            deleted = 0
        WHERE id = #{entry.id}
          AND provider_key = #{providerKey}
          AND deleted = 1
        """)
    int restoreDeletedDescriptor(@Param("entry") ProviderRegistryEntry entry,
                                 @Param("providerKey") String providerKey);
}
