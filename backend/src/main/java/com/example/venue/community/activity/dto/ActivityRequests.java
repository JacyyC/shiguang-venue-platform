package com.example.venue.community.activity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class ActivityRequests {
    private ActivityRequests() {
    }

    public static class Location {
        @NotBlank
        public String type;
        public Long venueId;
        public Long spaceId;
        @Size(max = 64)
        public String areaCode;
        public Long reservationId;
    }

    public static class CreateActivity {
        @NotNull
        public Long groupId;
        public Long referencePostId;
        @NotBlank
        public String activityType;
        @NotBlank
        @Size(max = 80)
        public String title;
        @Size(max = 1000)
        public String description;
        @NotNull
        public LocalDateTime startTime;
        @NotNull
        public LocalDateTime endTime;
        @Min(2)
        @Max(1000)
        public int capacityTotal;
        @Valid
        @NotNull
        public Location location;
    }

    public static class CreateCompanionRequest {
        @NotNull
        public Long groupId;
        public Long activityId;
        public Long referencePostId;
        @NotBlank
        public String activityType;
        @NotBlank
        @Size(max = 80)
        public String title;
        @Size(max = 1000)
        public String description;
        public LocalDateTime windowStart;
        public LocalDateTime windowEnd;
        @Min(2)
        @Max(100)
        public int capacityTotal;
        @Valid
        public Location location;
    }

    public static class UpdateLocation {
        @NotNull
        public Long versionNo;
        @Valid
        @NotNull
        public Location location;
    }

    public static class Decision {
        @Size(max = 64)
        public String reasonCode;
    }

    public static class DiscoveryQuery {
        @Size(max = 80)
        public String keyword;
        public String activityType;
        public LocalDateTime fromTime;
        public LocalDateTime toTime;
        public Long venueId;
        @Size(max = 64)
        public String areaCode;
        public Long cursorId;
        @Min(1)
        @Max(50)
        public int limit = 20;

        public String getKeyword() {
            return keyword;
        }

        public void setKeyword(String keyword) {
            this.keyword = keyword;
        }

        public String getActivityType() {
            return activityType;
        }

        public void setActivityType(String activityType) {
            this.activityType = activityType;
        }

        public LocalDateTime getFromTime() {
            return fromTime;
        }

        public void setFromTime(LocalDateTime fromTime) {
            this.fromTime = fromTime;
        }

        public LocalDateTime getToTime() {
            return toTime;
        }

        public void setToTime(LocalDateTime toTime) {
            this.toTime = toTime;
        }

        public Long getVenueId() {
            return venueId;
        }

        public void setVenueId(Long venueId) {
            this.venueId = venueId;
        }

        public String getAreaCode() {
            return areaCode;
        }

        public void setAreaCode(String areaCode) {
            this.areaCode = areaCode;
        }

        public Long getCursorId() {
            return cursorId;
        }

        public void setCursorId(Long cursorId) {
            this.cursorId = cursorId;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }
    }

    public static class ApplicationQuery {
        public String status;
        public Long cursorId;
        @Min(1)
        @Max(50)
        public int limit = 20;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Long getCursorId() {
            return cursorId;
        }

        public void setCursorId(Long cursorId) {
            this.cursorId = cursorId;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }
    }

    public static class RefreshMatches {
        @Min(1)
        @Max(50)
        public int limit = 20;
    }

    /** V02 日期目录查询：types 为空表示全部类型；时间窗口由前端按业务时区换算为绝对区间。 */
    public static class DirectoryQuery {
        @Size(max = 80)
        public String keyword;
        public String types;
        public String activityTypes;
        public LocalDateTime fromTime;
        public LocalDateTime toTime;
        public Long venueId;
        @Size(max = 64)
        public String areaCode;
        public Long groupId;
        public Long referencePostId;
        public Boolean onlyAvailable;
        public String cursor;
        @Min(1)
        @Max(50)
        public int limit = 20;

        public String getKeyword() {
            return keyword;
        }

        public void setKeyword(String keyword) {
            this.keyword = keyword;
        }

        public String getTypes() {
            return types;
        }

        public void setTypes(String types) {
            this.types = types;
        }

        public String getActivityTypes() {
            return activityTypes;
        }

        public void setActivityTypes(String activityTypes) {
            this.activityTypes = activityTypes;
        }

        public LocalDateTime getFromTime() {
            return fromTime;
        }

        public void setFromTime(LocalDateTime fromTime) {
            this.fromTime = fromTime;
        }

        public LocalDateTime getToTime() {
            return toTime;
        }

        public void setToTime(LocalDateTime toTime) {
            this.toTime = toTime;
        }

        public Long getVenueId() {
            return venueId;
        }

        public void setVenueId(Long venueId) {
            this.venueId = venueId;
        }

        public String getAreaCode() {
            return areaCode;
        }

        public void setAreaCode(String areaCode) {
            this.areaCode = areaCode;
        }

        public Long getGroupId() {
            return groupId;
        }

        public void setGroupId(Long groupId) {
            this.groupId = groupId;
        }

        public Long getReferencePostId() {
            return referencePostId;
        }

        public void setReferencePostId(Long referencePostId) {
            this.referencePostId = referencePostId;
        }

        public Boolean getOnlyAvailable() {
            return onlyAvailable;
        }

        public void setOnlyAvailable(Boolean onlyAvailable) {
            this.onlyAvailable = onlyAvailable;
        }

        public String getCursor() {
            return cursor;
        }

        public void setCursor(String cursor) {
            this.cursor = cursor;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }
    }
}
