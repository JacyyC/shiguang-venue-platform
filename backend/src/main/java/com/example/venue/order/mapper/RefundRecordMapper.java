package com.example.venue.order.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.order.entity.RefundRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RefundRecordMapper extends BaseMapper<RefundRecord> {
    @Select("select * from refund_record where id = #{id} limit 1 for update")
    RefundRecord selectByIdForUpdate(@Param("id") Long id);

    @Select("""
        <script>
        select r.*
        from refund_record r
        join biz_order o on o.id = r.order_id
        where 1 = 1
        <if test='venueId != null'>
          and o.venue_id = #{venueId}
        </if>
        <if test='venueIds != null and venueIds.size() > 0'>
          and o.venue_id in
          <foreach collection='venueIds' item='id' open='(' separator=',' close=')'>
            #{id}
          </foreach>
        </if>
        <if test='status != null and status != ""'>
          and r.status = #{status}
        </if>
        order by r.id desc
        </script>
        """)
    IPage<RefundRecord> selectAdminPage(Page<RefundRecord> page,
                                        @Param("venueId") Long venueId,
                                        @Param("venueIds") List<Long> venueIds,
                                        @Param("status") String status);
}
