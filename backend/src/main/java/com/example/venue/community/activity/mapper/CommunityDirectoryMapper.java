package com.example.venue.community.activity.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CommunityDirectoryMapper {

    @SelectProvider(type = CommunityDirectorySql.class, method = "page")
    List<DirectoryRow> selectPage(@Param("viewerUserId") Long viewerUserId,
                                  @Param("keyword") String keyword,
                                  @Param("activityTypeList") List<String> activityTypeList,
                                  @Param("fromTime") LocalDateTime fromTime,
                                  @Param("toTime") LocalDateTime toTime,
                                  @Param("venueId") Long venueId,
                                  @Param("areaCode") String areaCode,
                                  @Param("groupId") Long groupId,
                                  @Param("referencePostId") Long referencePostId,
                                  @Param("onlyAvailable") boolean onlyAvailable,
                                  @Param("types") List<String> types,
                                  @Param("cursorStart") LocalDateTime cursorStart,
                                  @Param("cursorType") String cursorType,
                                  @Param("cursorId") Long cursorId,
                                  @Param("now") LocalDateTime now,
                                  @Param("limit") int limit);

    @SelectProvider(type = CommunityDirectorySql.class, method = "countByType")
    List<TypeCountRow> countByType(@Param("viewerUserId") Long viewerUserId,
                                   @Param("keyword") String keyword,
                                   @Param("activityTypeList") List<String> activityTypeList,
                                   @Param("fromTime") LocalDateTime fromTime,
                                   @Param("toTime") LocalDateTime toTime,
                                   @Param("venueId") Long venueId,
                                   @Param("areaCode") String areaCode,
                                   @Param("groupId") Long groupId,
                                   @Param("referencePostId") Long referencePostId,
                                   @Param("onlyAvailable") boolean onlyAvailable,
                                   @Param("now") LocalDateTime now);

    @SelectProvider(type = CommunityDirectorySql.class, method = "countByDate")
    List<DateCountRow> countByDate(@Param("viewerUserId") Long viewerUserId,
                                   @Param("keyword") String keyword,
                                   @Param("activityTypeList") List<String> activityTypeList,
                                   @Param("fromTime") LocalDateTime fromTime,
                                   @Param("toTime") LocalDateTime toTime,
                                   @Param("venueId") Long venueId,
                                   @Param("areaCode") String areaCode,
                                   @Param("groupId") Long groupId,
                                   @Param("referencePostId") Long referencePostId,
                                   @Param("onlyAvailable") boolean onlyAvailable,
                                   @Param("now") LocalDateTime now);

    @SelectProvider(type = CommunityDirectorySql.class, method = "hostedPage")
    List<DirectoryRow> selectHostedPage(@Param("ownerUserId") Long ownerUserId,
                                        @Param("cursorTime") LocalDateTime cursorTime,
                                        @Param("cursorType") String cursorType,
                                        @Param("cursorId") Long cursorId,
                                        @Param("limit") int limit);

    @SelectProvider(type = CommunityDirectorySql.class, method = "hostedCount")
    long countHosted(@Param("ownerUserId") Long ownerUserId);

    class DirectoryRow {
        public String sourceType;
        public Long sourceId;
        public Long groupId;
        public Long referencePostId;
        public Long ownerUserId;
        public String activityType;
        public String title;
        public String description;
        public LocalDateTime sortStart;
        public LocalDateTime sortEnd;
        public String locationType;
        public Long venueId;
        public Long spaceId;
        public String areaCode;
        public String locationCommitmentStatus;
        public Integer capacityTotal;
        public Integer participantCount;
        public String status;
        public Long linkedActivityId;
        public LocalDateTime updateTime;
    }

    class TypeCountRow {
        public String sourceType;
        public Long total;
    }

    class DateCountRow {
        public LocalDate countDate;
        public Long total;
    }
}
