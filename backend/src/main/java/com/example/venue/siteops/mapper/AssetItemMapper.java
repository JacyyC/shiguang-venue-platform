package com.example.venue.siteops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.siteops.entity.AssetItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssetItemMapper extends BaseMapper<AssetItem> {
    @Select("select * from asset_item where id = #{id} and deleted = 0 for update")
    AssetItem selectByIdForUpdate(@Param("id") Long id);
}
