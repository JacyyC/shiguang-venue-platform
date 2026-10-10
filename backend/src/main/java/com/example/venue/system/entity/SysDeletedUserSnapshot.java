package com.example.venue.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("sys_deleted_user_snapshot")
public class SysDeletedUserSnapshot {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String username;
    public String nickname;
    public String phone;
    public String email;
    public String avatar;
    public String status;
    public String roleKeys;
    public LocalDateTime lastLoginTime;
    public LocalDateTime userCreateTime;
    public LocalDateTime userUpdateTime;
    public Long deletedBy;
    public String deletedByUsername;
    public LocalDateTime deletedTime;
    public LocalDateTime deidentifyAfter;
    public LocalDateTime deidentifiedAt;
}
