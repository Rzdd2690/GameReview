package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    Result isFollow(Long id, Boolean isFollow);

    Result orNot(Long id);

    Result commonAttention(Long id);
}
