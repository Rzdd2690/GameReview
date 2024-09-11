package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOW;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Autowired
    IUserService userService;
    /**
     * 关注/取关当前博客
     *
     * @param followUserId
     * @param isFollow
     * @return
     */
    @Override
    public Result isFollow(Long followUserId, Boolean isFollow) {
        //1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOW + userId;
        // 1.判断到底是关注还是取关
        if (isFollow) {
            // 2.关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if(isSuccess) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 3.取关，删除 delete from tb_follow where user_id = ? and follow_user_id = ?
            // 条件构造 remove一个对象，对象长XXXX样，传条件
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if(isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查看当前博客有没有被关注
     *
     * @param followUserId
     * @return
     */
    @Override
    public Result orNot(Long followUserId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        // 3.判断
        return Result.ok(count > 0);
    }

    /**
     * 共同关注
     * @param id
     * @return
     */
    @Override
    public Result commonAttention(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOW + userId;
        String key2 = FOLLOW + id;
        // 通过本人ID，以及我查看的当前用户ID，通过intersect方法直接求交集，返回该集合
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        // 如果该集合为空
        if (intersect==null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 拿到的集合为一堆以逗号为分隔符，用户id的字符串，我需要将他们遍历出来，转换为存放String的名称
        // 通过in查询，把id都查出来，返回一个放user的集合
        // 将user赋值给userDTO，安全
        List<UserDTO> users = userService.query()
                .in("id", intersect)
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}