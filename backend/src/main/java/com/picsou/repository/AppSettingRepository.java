package com.picsou.repository;

import com.picsou.model.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {

    Optional<AppSetting> findByKey(String key);

    /**
     * Compare-and-set update used by the setup wizard to atomically claim the
     * PENDING_ADMIN → IN_PROGRESS transition when two concurrent bootstraps
     * race. Returns 0 if another caller already claimed the state.
     */
    @Modifying
    @Query("UPDATE AppSetting s SET s.value = :newValue WHERE s.key = :key AND s.value = :expectedValue")
    int compareAndSet(@Param("key") String key,
                      @Param("expectedValue") String expectedValue,
                      @Param("newValue") String newValue);

    /**
     * Writes {@code key} unless a row already holds it, in one statement: of two callers racing
     * for the same key, the first value stays. Returns 1 when this call wrote the row.
     */
    @Modifying
    @Query(value = "INSERT INTO app_setting (setting_key, value) VALUES (:key, :value) "
        + "ON CONFLICT (setting_key) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("key") String key, @Param("value") String value);

    /**
     * Compare-and-delete in one statement: removes {@code key} only while it still holds
     * {@code value}. A bulk DELETE, so two callers removing the same value both succeed, one
     * with 0 rows.
     */
    @Modifying
    @Query("DELETE FROM AppSetting s WHERE s.key = :key AND s.value = :value")
    int deleteIfValue(@Param("key") String key, @Param("value") String value);
}
