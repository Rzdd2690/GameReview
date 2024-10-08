package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result queryHotBlog(Integer current);

    Result queryById(String id);

    Result likeBlog(Long id);

    Result queryLikeList(String id);

    Result saveWithPush(Blog blog);

    Result queryBlogOfFollow(Long max, Integer offset);

    Result queryOthersBlog(Integer current, Long id);
}
