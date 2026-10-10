package com.example.venue.community;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.example.venue.community.entity.CommunityComment;
import com.example.venue.community.entity.CommunityGroup;
import com.example.venue.community.entity.CommunityGroupMember;
import com.example.venue.community.entity.CommunityPost;
import com.example.venue.community.entity.CommunityUserBlock;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityEntityPersistenceContractTest {
    @Test
    void nullableLifecycleFieldsAreAlwaysIncludedInMyBatisUpdates() throws Exception {
        assertAlways(CommunityGroup.class,
            "hiddenAt", "deletedAt", "retainUntil");
        assertAlways(CommunityGroupMember.class,
            "reviewedBy", "reviewedAt", "joinedAt", "leftAt");
        assertAlways(CommunityPost.class,
            "hiddenBy", "hiddenScope", "hiddenReasonCode", "hiddenAt", "deletedAt", "retainUntil");
        assertAlways(CommunityComment.class,
            "hiddenBy", "hiddenScope", "hiddenReasonCode", "hiddenAt", "deletedAt", "retainUntil");
        assertAlways(CommunityUserBlock.class, "removedAt");
    }

    private void assertAlways(Class<?> entityType, String... fieldNames) throws Exception {
        for (String fieldName : List.of(fieldNames)) {
            Field field = entityType.getField(fieldName);
            TableField tableField = field.getAnnotation(TableField.class);
            assertThat(tableField)
                .as("%s.%s must persist a transition back to null", entityType.getSimpleName(), fieldName)
                .isNotNull();
            assertThat(tableField.updateStrategy())
                .as("%s.%s update strategy", entityType.getSimpleName(), fieldName)
                .isEqualTo(FieldStrategy.ALWAYS);
        }
    }
}
