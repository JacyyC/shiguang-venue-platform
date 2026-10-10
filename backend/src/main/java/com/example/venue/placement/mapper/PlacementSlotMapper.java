package com.example.venue.placement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.placement.entity.PlacementSlot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlacementSlotMapper extends BaseMapper<PlacementSlot> {

    @Select("SELECT * FROM placement_slot WHERE slot_code = #{slotCode} LIMIT 1")
    PlacementSlot findByCode(@Param("slotCode") String slotCode);
}
