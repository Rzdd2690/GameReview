package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * 博客功能相关接口
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    /**
     * 保存博客
     * @param blog
     * @return
     */
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
       return blogService.saveWithPush(blog);
    }

    /**
     * 给博客点赞或者取消点赞
     * @param id
     * @return
     */
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        // 修改点赞数量
        return blogService.likeBlog(id);
    }

    /**
     * 根据(当前登录者)用户ID查询博客明细
     * @param current
     * @return
     */
    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /**
     * 查看其他用户博客
     */
    @GetMapping("/of/user")
    public Result queryOthersBlog(@RequestParam(value = "current",defaultValue = "1")Integer current,@RequestParam(value = "id")Long id){
        return  blogService.queryOthersBlog(current,id);
    }
    /**
     *查询热门博客
     * @param current
     * @return
     */
    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    /**
     * 通过用户ID查询博客
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public Result queryById(@PathVariable("id") String id){
        return blogService.queryById(id);
    }

    /**
     * 查询当前博客点赞前五个人，返回值为用户集合
     * @param id
     * @return
     */
    @GetMapping("/likes/{id}")
    public Result queryLikeList(@PathVariable("id") String id){
        return blogService.queryLikeList(id);
    }


    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset){
        return blogService.queryBlogOfFollow(max,offset);
    }
}
