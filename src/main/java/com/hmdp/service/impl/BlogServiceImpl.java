package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.entity.Blog;
import com.hmdp.dto.entity.Follow;
import com.hmdp.dto.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;


@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    IUserService userService;
    @Autowired
    IFollowService followService;
    @Autowired
    StringRedisTemplate stringRedisTemplate;

    /**
     * 查询点赞热门榜
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            isLike(blog);
        });
        return Result.ok(records);
    }
    /**
     * 查看当前博客是否被当前用户点赞
     * @param blog
     */
    private void isLike(Blog blog){
        UserDTO user = UserHolder.getUser();
        if(user==null) {
            return ;
        }
        Long id = blog.getId();
        // 2.判断是否点赞
        String key = BLOG_LIKED_KEY + blog.getId().toString();
        // 判断当前redis是否存在缓存，点了赞就有缓存，有的话，说明点过了
        Double score = stringRedisTemplate.opsForZSet().score(key, id.toString());
        // 根据返回值判断当前博客被当前用户是否点赞,是否被点赞 该属性没有在数据表中真是存在，而是业务需要
        blog.setIsLike(score!=null);
    }
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 查看笔记明细
     * @param id
     * @return
     */
    @Override
    public Result queryById(String id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        queryBlogUser(blog);
        // 判断是否点赞
        isLike(blog);
        return Result.ok(blog);
    }

    /**
     * 给博客点赞
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // 1.判断当前用户是否登录
        Long userId = UserHolder.getUser().getId();
        if (userId == null) {
            return Result.fail("请先登录!");
        }
          // 2.判断是否点赞
        String key = BLOG_LIKED_KEY + id.toString();
        // 判断当前redis是否存在点赞的缓存，点了赞就有缓存，有的话，说明点过了
        Double isMember = stringRedisTemplate.opsForZSet().score(key, id.toString());
        if (isMember==null) {
            //3.如果未点赞，可以点赞
            //3.1 数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2 保存用户到Redis的set集合
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, id.toString(),System.currentTimeMillis());
            }
        } else {
            //4.如果已点赞，取消点赞
            //4.1 数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //4.2 把用户从Redis的set集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, id.toString());
            }
        }
        return Result.ok();
    }
    /**
     * 查询点赞列表前五
     */
    @Override
    public Result queryLikeList(String id) {
        // 查询点赞列表
        // 获取前五个用户的id
        String key = BLOG_LIKED_KEY + id;
        Set<String> range = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(range==null || range.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 查询用户名称
        List<Long> ids = range.stream().map(Long::valueOf).collect(Collectors.toList());
        // 这里ids为集合，需要将集合转换为以逗号分隔开的字符串
        String idStr =StrUtil.join(",", ids);
        // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids)
                // last 表示在sql的末尾加上一个条件，同时传入的应该为字符串 ，需要转换成以逗号分隔的串
                .last("ORDER BY FIELD(id," + idStr + ")")
                // list表示返回list的集合形式
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    /**
     * 保存并发送（给自己的粉丝的收件箱，即发给粉丝id的缓存中）
     * @param blog
     * @return
     */
    @Override
    public Result saveWithPush(Blog blog) {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("网络出现问题，请稍后重试~");
        }
        List<Follow> followUserId = followService.query().eq("follow_user_id", userId).list();
        for (Follow follow : followUserId) {
            // 4.1.获取粉丝id
            Long userId2 = follow.getUserId();
            // 4.2.推送
            String key = FEED_KEY + userId2;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
            return Result.ok(blog.getId());
    }

    /**
     *
     * @param max
     * @param offset
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
//        Long userId = UserHolder.getUser().getId();
//        String key = FEED_KEY + userId;
//        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().
//                reverseRangeByScoreWithScores(key, 0, max, offset, 2);
//        if(typedTuples.isEmpty()||typedTuples == null){
//            return Result.ok();
//        }
//        List<Long> ids = new ArrayList<>(typedTuples.size());
//        Long minTime = 0L;
//        int OSet = 1;
//        // 遍历获取博客ID号以及对应的时间戳
//            for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
//                ids.add(Long.valueOf(typedTuple.getValue()));
//                Long time = typedTuple.getScore().longValue();
//                if (time == minTime) {
//                    OSet++;
//                } else {
//                    minTime = time;
//                    OSet = 1;
//                }
//            }
//        String idStr = StrUtil.join(",", ids);
//        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
//        for (Blog blog : blogs) {
//            queryBlogUser(blog);
//            isLike(blog);
//        }
//        ScrollResult r = new ScrollResult();
//        r.setList(blogs);
//        r.setOffset(OSet);
//        r.setMinTime(minTime);
        return Result.ok();
    }
}
