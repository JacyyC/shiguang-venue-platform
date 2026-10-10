package com.example.venue.common;

import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.entity.VenueSpace;
import com.example.venue.venue.mapper.VenueMapper;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import org.springframework.stereotype.Service;

/**
 * 跨域共享的实体存在性校验 helper。
 *
 * <p>用于在写操作前校验关联实体（用户、场馆、场地）存在且处于有效状态，避免写入指向
 * 已删除、已禁用或不存在的实体而污染业务数据。P2 权限校验类缺陷（P2-03/08/09/11/13）
 * 复用本类保证实体存在与状态，场馆归属、角色权限等业务语义校验不在此处，由各领域
 * Service 按场景实现。
 *
 * <p>SysUser/Venue/VenueSpace 实体未配置 {@code @TableLogic}，{@code selectById} 与
 * {@code selectByIdForUpdate} 不会自动过滤逻辑删除字段，因此本类在内存中显式校验
 * {@code deleted} 与 {@code status}。
 *
 * <p>DC-03 收口：{@link #requireVenueForUpdate} / {@link #requireSpaceForUpdate} 将
 * ReservationService/VenueInventoryService 原各自重复定义的锁查询 helper 统一到此处，
 * 锁顺序（venue→space）仍由调用方保证，本类不自动连锁。
 */
@Service
public class EntityExistenceService {
    private final SysUserMapper userMapper;
    private final VenueMapper venueMapper;
    private final VenueSpaceMapper spaceMapper;

    public EntityExistenceService(SysUserMapper userMapper, VenueMapper venueMapper, VenueSpaceMapper spaceMapper) {
        this.userMapper = userMapper;
        this.venueMapper = venueMapper;
        this.spaceMapper = spaceMapper;
    }

    /**
     * 校验用户存在且处于 ACTIVE 状态。
     *
     * @param userId 用户 ID，为 null 时拒绝
     * @throws BusinessException 用户不存在、已逻辑删除或非 ACTIVE 时抛 badRequest
     */
    public void requireActiveUser(Long userId) {
        if (userId == null) {
            throw BusinessException.badRequest("用户ID不能为空");
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null || (user.deleted != null && user.deleted != 0)) {
            throw BusinessException.badRequest("用户不存在");
        }
        if (!Constants.ACTIVE.equals(user.status)) {
            throw BusinessException.badRequest("用户已禁用");
        }
    }

    /**
     * 校验场馆存在（未逻辑删除）。
     *
     * @param venueId 场馆 ID，为 null 时拒绝
     * @throws BusinessException 场馆不存在或已逻辑删除时抛 badRequest
     */
    public void requireVenueExists(Long venueId) {
        if (venueId == null) {
            throw BusinessException.badRequest("场馆ID不能为空");
        }
        Venue venue = venueMapper.selectById(venueId);
        if (venue == null || (venue.deleted != null && venue.deleted != 0)) {
            throw BusinessException.badRequest("场馆不存在");
        }
    }

    /**
     * FOR UPDATE 锁定场馆行并校验存在与未逻辑删除。
     *
     * <p>用于预约/库存写操作前获取场馆行锁（venue→space 锁顺序的第一道），替代
     * ReservationService/VenueInventoryService 原各自重复定义的本地 helper。
     *
     * @param venueId 场馆 ID，为 null 时抛 badRequest
     * @return 锁定后的场馆实体
     * @throws BusinessException 场馆不存在或已逻辑删除时抛 notFound
     */
    public Venue requireVenueForUpdate(Long venueId) {
        if (venueId == null) {
            throw BusinessException.badRequest("场馆ID不能为空");
        }
        Venue venue = venueMapper.selectByIdForUpdate(venueId);
        if (venue == null || Integer.valueOf(1).equals(venue.deleted)) {
            throw BusinessException.notFound("场馆不存在");
        }
        return venue;
    }

    /**
     * FOR UPDATE 锁定场地行并校验存在与未逻辑删除。
     *
     * <p>用于预约/库存写操作前获取场地行锁（venue→space 锁顺序的第二道），替代
     * ReservationService/VenueInventoryService 原各自重复定义的本地 helper。
     *
     * @param spaceId 场地 ID，为 null 时抛 badRequest
     * @return 锁定后的场地实体
     * @throws BusinessException 场地不存在或已逻辑删除时抛 notFound
     */
    public VenueSpace requireSpaceForUpdate(Long spaceId) {
        if (spaceId == null) {
            throw BusinessException.badRequest("场地ID不能为空");
        }
        VenueSpace space = spaceMapper.selectByIdForUpdate(spaceId);
        if (space == null || Integer.valueOf(1).equals(space.deleted)) {
            throw BusinessException.notFound("场地不存在");
        }
        return space;
    }
}
