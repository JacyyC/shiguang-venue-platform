package com.example.venue.community.service;

import com.example.venue.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommunityTextPolicyTest {
    private final CommunityTextPolicy policy = new CommunityTextPolicy();

    @ParameterizedTest
    @ValueSource(strings = {
        "手机号：13800138000",
        "联系号码 19912345678"
    })
    void rejectsMobilePhoneNumbers(String value) {
        assertPrivateInformationRejected(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "邮箱：member@example.com",
        "write to user.name+community@example.org"
    })
    void rejectsEmailAddresses(String value) {
        assertPrivateInformationRejected(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "微信号：venue_friend",
        "wx:study_group",
        "QQ号 12345678",
        "wechat community-user"
    })
    void rejectsWechatAndQqAccounts(String value) {
        assertPrivateInformationRejected(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "家庭地址：海淀区某小区 3 号楼",
        "门牌号: 12-3-402",
        "宿舍号：东区 6 栋 201"
    })
    void rejectsExplicitPrivateAddresses(String value) {
        assertPrivateInformationRejected(value);
    }

    @Test
    void stripsHtmlBeforeReturningPlainText() {
        assertThat(policy.required("  周末<b>羽毛球</b><script>alert(1)</script>  ", 100, "正文"))
            .isEqualTo("周末羽毛球");
    }

    @Test
    void cannotHidePrivateInformationInsideHtmlTags() {
        assertPrivateInformationRejected("联系 <strong>member@example.com</strong>");
    }

    private void assertPrivateInformationRejected(String value) {
        assertThatThrownBy(() -> policy.required(value, 200, "正文"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不能包含联系方式或私人精确位置");
    }
}
