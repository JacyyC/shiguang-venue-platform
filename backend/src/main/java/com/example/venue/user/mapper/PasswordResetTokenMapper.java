package com.example.venue.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.user.entity.PasswordResetToken;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface PasswordResetTokenMapper extends BaseMapper<PasswordResetToken> {
    @Update("update password_reset_token set used_at = #{usedAt} where user_id = #{userId} and used_at is null")
    int invalidateActiveByUserId(@Param("userId") Long userId, @Param("usedAt") LocalDateTime usedAt);

    @Select("select * from password_reset_token where token_hash = #{tokenHash} and used_at is null and expires_at > #{now} limit 1 for update")
    PasswordResetToken findActiveByHashForUpdate(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    @Update("update password_reset_token set used_at = #{usedAt} where id = #{id} and used_at is null")
    int consume(@Param("id") Long id, @Param("usedAt") LocalDateTime usedAt);
}
