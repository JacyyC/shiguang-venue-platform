package com.example.venue.community.activity.mapper;

/**
 * 目录聚合 SQL：活动与找搭子两条分支统一列后 UNION ALL，
 * 可见性/屏蔽/引用帖谓词在此单点维护，禁止在查询间复制出分叉的权限口径。
 */
public final class CommunityDirectorySql {
    private CommunityDirectorySql() {
    }

    static final String UNION_COLUMNS = """
        select
          x.source_type, x.source_id, x.group_id, x.reference_post_id, x.owner_user_id,
          x.activity_type, x.title, x.description, x.sort_start, x.sort_end,
          x.location_type, x.venue_id, x.space_id, x.area_code,
          x.location_commitment_status, x.capacity_total, x.participant_count,
          x.status, x.linked_activity_id, x.update_time
        from (
        """;

    private static String activityBranch(String filters) {
        return """
            select
              'ACTIVITY' as source_type, a.id as source_id, a.group_id, a.reference_post_id,
              a.organizer_user_id as owner_user_id, a.activity_type, a.title, a.description,
              a.start_time as sort_start, a.end_time as sort_end,
              a.location_type, a.venue_id, a.space_id, a.area_code,
              a.location_commitment_status, a.capacity_total, a.participant_count,
              a.status, cast(null as unsigned) as linked_activity_id, a.update_time
            from community_activity a
            join community_group g on g.id = a.group_id and g.status = 'ACTIVE'
            left join community_post p on p.id = a.reference_post_id
            where a.status in ('PUBLISHED','FULL')
              and a.end_time > #{now}
              and (a.reference_post_id is null
                or (p.status = 'PUBLISHED' and p.group_id = a.group_id))
              and (g.visibility = 'PLATFORM_VISIBLE' or exists (
                select 1 from community_group_member gm
                where gm.group_id = a.group_id and gm.user_id = #{viewerUserId} and gm.status = 'ACTIVE'
              ))
              and not exists (
                select 1 from community_user_block b
                where b.status = 'ACTIVE' and (
                  (b.blocker_user_id = #{viewerUserId}
                    and b.blocked_user_id in (a.organizer_user_id, g.owner_user_id, p.author_user_id))
                  or (b.blocked_user_id = #{viewerUserId}
                    and b.blocker_user_id in (a.organizer_user_id, g.owner_user_id, p.author_user_id))
                )
              )
            """ + filters;
    }

    private static String companionBranch(String filters) {
        return """
            select
              'COMPANION' as source_type, r.id as source_id, r.group_id, r.reference_post_id,
              r.owner_user_id, r.activity_type, r.title, r.description,
              r.window_start as sort_start, r.window_end as sort_end,
              r.location_type, r.venue_id, r.space_id, r.area_code,
              r.location_commitment_status, r.capacity_total, r.participant_count,
              r.status, r.activity_id as linked_activity_id, r.update_time
            from community_companion_request r
            join community_group g on g.id = r.group_id and g.status = 'ACTIVE'
            left join community_post p on p.id = r.reference_post_id
            left join community_activity a on a.id = r.activity_id
            left join community_post ap on ap.id = a.reference_post_id
            where r.status = 'PUBLISHED'
              and r.window_end > #{now}
              and (r.reference_post_id is null
                or (p.status = 'PUBLISHED' and p.group_id = r.group_id))
              and (r.activity_id is null
                or (a.status in ('PUBLISHED','FULL') and a.group_id = r.group_id
                  and a.end_time > #{now}
                  and (a.reference_post_id is null
                    or (ap.status = 'PUBLISHED' and ap.group_id = a.group_id))))
              and (g.visibility = 'PLATFORM_VISIBLE' or exists (
                select 1 from community_group_member gm
                where gm.group_id = r.group_id and gm.user_id = #{viewerUserId} and gm.status = 'ACTIVE'
              ))
              and not exists (
                select 1 from community_user_block b
                where b.status = 'ACTIVE' and (
                  (b.blocker_user_id = #{viewerUserId}
                    and b.blocked_user_id in (r.owner_user_id, g.owner_user_id, p.author_user_id,
                      a.organizer_user_id, ap.author_user_id))
                  or (b.blocked_user_id = #{viewerUserId}
                    and b.blocker_user_id in (r.owner_user_id, g.owner_user_id, p.author_user_id,
                      a.organizer_user_id, ap.author_user_id))
                )
              )
            """ + filters;
    }

    /** 非类型筛选：两条分支共用同一套口径（含组名搜索，CG-02）。 */
    private static String activityFilters() {
        return """
              <if test="keyword != null">
                and (a.title like concat('%', #{keyword}, '%')
                  or a.description like concat('%', #{keyword}, '%')
                  or g.name like concat('%', #{keyword}, '%'))
              </if>
              <if test="activityTypeList != null and activityTypeList.size() &gt; 0">
                and a.activity_type in
                <foreach collection="activityTypeList" item="at" open="(" separator="," close=")">#{at}</foreach>
              </if>
              <if test="fromTime != null">
                and a.end_time &gt; #{fromTime}
              </if>
              <if test="toTime != null">
                and a.start_time &lt; #{toTime}
              </if>
              <if test="venueId != null">
                and a.venue_id = #{venueId}
              </if>
              <if test="areaCode != null">
                and a.area_code = #{areaCode}
              </if>
              <if test="groupId != null">
                and a.group_id = #{groupId}
              </if>
              <if test="referencePostId != null">
                and a.reference_post_id = #{referencePostId}
              </if>
            """;
    }

    private static String companionFilters() {
        return """
              <if test="keyword != null">
                and (r.title like concat('%', #{keyword}, '%')
                  or r.description like concat('%', #{keyword}, '%')
                  or g.name like concat('%', #{keyword}, '%'))
              </if>
              <if test="activityTypeList != null and activityTypeList.size() &gt; 0">
                and r.activity_type in
                <foreach collection="activityTypeList" item="at" open="(" separator="," close=")">#{at}</foreach>
              </if>
              <if test="fromTime != null">
                and r.window_end &gt; #{fromTime}
              </if>
              <if test="toTime != null">
                and r.window_start &lt; #{toTime}
              </if>
              <if test="venueId != null">
                and r.venue_id = #{venueId}
              </if>
              <if test="areaCode != null">
                and r.area_code = #{areaCode}
              </if>
              <if test="groupId != null">
                and r.group_id = #{groupId}
              </if>
              <if test="referencePostId != null">
                and r.reference_post_id = #{referencePostId}
              </if>
            """;
    }

    /** 只看余量是非类型筛选，计数与分页同口径；types 只收窄分页记录，不收窄计数（tab 数不随当前 tab 缩窄）。 */
    private static final String ONLY_AVAILABLE_OUTER = """
              <if test="onlyAvailable">
                and (x.status = 'PUBLISHED' and x.participant_count &lt; x.capacity_total)
              </if>
            """;

    public static String page() {
        return """
            <script>
            """ + UNION_COLUMNS
            + activityBranch(activityFilters())
            + "        union all\n"
            + companionBranch(companionFilters())
            + """
            ) x
            where 1 = 1
            """ + ONLY_AVAILABLE_OUTER + """
              <if test="types != null and types.size() &gt; 0">
                and x.source_type in
                <foreach collection="types" item="item" open="(" separator="," close=")">#{item}</foreach>
              </if>
              <if test="cursorStart != null">
                and (x.sort_start &gt; #{cursorStart}
                  or (x.sort_start = #{cursorStart} and x.source_type &gt; #{cursorType})
                  or (x.sort_start = #{cursorStart} and x.source_type = #{cursorType}
                    and x.source_id &gt; #{cursorId}))
              </if>
            order by x.sort_start asc, x.source_type asc, x.source_id asc
            limit #{limit}
            </script>
            """;
    }

    public static String countByType() {
        return """
            <script>
            select x.source_type as source_type, count(*) as total from (
            """ + activityBranch(activityFilters())
            + "        union all\n"
            + companionBranch(companionFilters())
            + """
            ) x
            where 1 = 1
            """ + ONLY_AVAILABLE_OUTER + """
            group by x.source_type
            </script>
            """;
    }

    public static String countByDate() {
        return """
            <script>
            select date(x.sort_start) as count_date, count(*) as total from (
            """ + activityBranch(activityFilters())
            + "        union all\n"
            + companionBranch(companionFilters())
            + """
            ) x
            where 1 = 1
            """ + ONLY_AVAILABLE_OUTER + """
            group by date(x.sort_start)
            order by count_date asc
            </script>
            """;
    }

    public static String hostedPage() {
        return """
            <script>
            """ + UNION_COLUMNS + """
            select
              'ACTIVITY' as source_type, a.id as source_id, a.group_id, a.reference_post_id,
              a.organizer_user_id as owner_user_id, a.activity_type, a.title, a.description,
              a.start_time as sort_start, a.end_time as sort_end,
              a.location_type, a.venue_id, a.space_id, a.area_code,
              a.location_commitment_status, a.capacity_total, a.participant_count,
              a.status, cast(null as unsigned) as linked_activity_id, a.update_time
            from community_activity a
            where a.organizer_user_id = #{ownerUserId}
            union all
            select
              'COMPANION' as source_type, r.id as source_id, r.group_id, r.reference_post_id,
              r.owner_user_id, r.activity_type, r.title, r.description,
              r.window_start as sort_start, r.window_end as sort_end,
              r.location_type, r.venue_id, r.space_id, r.area_code,
              r.location_commitment_status, r.capacity_total, r.participant_count,
              r.status, r.activity_id as linked_activity_id, r.update_time
            from community_companion_request r
            where r.owner_user_id = #{ownerUserId}
            ) x
            where 1 = 1
              <if test="cursorTime != null">
                and (x.update_time &lt; #{cursorTime}
                  or (x.update_time = #{cursorTime} and x.source_type &lt; #{cursorType})
                  or (x.update_time = #{cursorTime} and x.source_type = #{cursorType}
                    and x.source_id &lt; #{cursorId}))
              </if>
            order by x.update_time desc, x.source_type desc, x.source_id desc
            limit #{limit}
            </script>
            """;
    }

    public static String hostedCount() {
        return """
            select count(*) as total from (
            select a.id from community_activity a where a.organizer_user_id = #{ownerUserId}
            union all
            select r.id from community_companion_request r where r.owner_user_id = #{ownerUserId}
            ) x
            """;
    }
}
