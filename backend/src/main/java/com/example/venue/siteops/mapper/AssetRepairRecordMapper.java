package com.example.venue.siteops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.siteops.entity.AssetRepairRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssetRepairRecordMapper extends BaseMapper<AssetRepairRecord> {
    // AUD-P2-02: 行锁读取维修单，防止完成/取消并发覆盖终态
    @Select("select * from asset_repair_record where id = #{id} and deleted = 0 for update")
    AssetRepairRecord selectByIdForUpdate(@Param("id") Long id);
}
