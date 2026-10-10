package com.example.venue.member.profile;

import com.example.venue.member.entity.MemberAccount;
import com.example.venue.member.mapper.MemberAccountMapper;
import com.example.venue.placement.profile.PlacementMemberProfileReader;
import org.springframework.stereotype.Component;

/**
 * 会员域提供的宣传位会员等级窄读取实现。宣传域只通过 PlacementMemberProfileReader SPI
 * 读取当前用户 levelCode，不直查会员 Mapper。无会员账户时返回 null，
 * 决策按 ALL_AUTHENTICATED 受众放行。
 */
@Component
public class MemberProfileReaderImpl implements PlacementMemberProfileReader {

    private final MemberAccountMapper memberAccountMapper;

    public MemberProfileReaderImpl(MemberAccountMapper memberAccountMapper) {
        this.memberAccountMapper = memberAccountMapper;
    }

    @Override
    public String currentLevelCode(Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        MemberAccount account = memberAccountMapper.findByUserId(userId);
        return account == null ? null : account.levelCode;
    }
}
