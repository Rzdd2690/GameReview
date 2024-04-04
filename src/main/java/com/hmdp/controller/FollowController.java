package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Autowired
    IFollowService followService;
    /**
     * 关注或取关当前博主
     * @param id
     * @param isFollow
     * @return
     */
    @PutMapping("/{id}/{isFollow}")
    public Result isFollow(@PathVariable("id")Long id , @PathVariable("isFollow")Boolean isFollow){
        return followService.isFollow(id,isFollow);
    }
    /**
     * 查看是否关注
     */
    @GetMapping("/or/not/{id}")
    public Result orNot(@PathVariable("id") Long id){
        return followService.orNot(id);
    }
    @GetMapping("/common/{id}")
    public Result commonAttention(@PathVariable("id")Long id){
        return followService.commonAttention(id);
    }
}
