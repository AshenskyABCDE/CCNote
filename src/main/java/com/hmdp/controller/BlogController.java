package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.websocket.server.PathParam;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author ashensky
 * @since 2024-6-20
 */
@RestController
@RequestMapping("/blog")
public class BlogController {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IBlogService blogService;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
//        // 判断是否已经有点过赞
//        stringRedisTemplate.opsForValue().get("blogLike:"+id);
//        // 修改点赞数量 update tb_blog set like = like + 1 where id is "id"
//        blogService.update()
//                .setSql("liked = liked + 1").eq("id", id).update();
        return blogService.likeBlog(id);
    }

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

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogByid(id);
    }
    @GetMapping("/likes/{id}")
    public Result queryBloglikesById(@PathVariable("id") Long id) {

        return blogService.queryBlogLikes(id);
    }

    // 用户主页根据用户个人资料对其进行分页
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @PathParam("id") Long id) {

        // 根据用户查询，分页
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取页面数据
        List<Blog> records = page.getRecords();
        // 返回
        return Result.ok(records);
    }
    @GetMapping("/of/follow")
    public  Result queryBlogOfFollow(@RequestParam("lastId") Long max, @RequestParam(value="offset", defaultValue = "0") Integer offset) {
        return blogService.queryBlogOfFollow(max, offset);
    }
}
