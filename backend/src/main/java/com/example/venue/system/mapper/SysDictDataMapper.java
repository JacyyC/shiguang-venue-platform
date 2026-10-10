package com.example.venue.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.system.entity.SysDictData;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysDictDataMapper extends BaseMapper<SysDictData> {
    @Select("select * from sys_dict_data where dict_type = #{type} and status = 'ACTIVE' order by sort_order, id")
    List<SysDictData> findByType(@Param("type") String type);
}
