package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author ashensky
 * @since 2024-6-20
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            this.getResult(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogByid(Long id) {
        Blog blog = getById(id);
        if(blog == null) {
            return Result.fail("笔记不存在，请刷新");
        }
        // 查询blog的用户
        getResult(blog);
        // 查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if(user == null) {
            // 用户未登录
            return ;
        }
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "blog:liked1:" + blog.getId();
        // 判断是否点赞过
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public  Result likeBlog(Long id) {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "blog:liked1:" + id;
        // 判断是否点赞过
        Double score =  stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null) {
            // update tb_blog set like = like -1 where id = "id"
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id",id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id",id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }
    // 直接用 redis 判断是够点赞过的方法
    @Override
    public Result likeBlog2(Long id) {
        Long userId = UserHolder.getUser().getId();
        String value = stringRedisTemplate.opsForValue().get("blog:liked2:" + userId + id);
        if(value == null || value == "remove") {
            Boolean isSuccess = update().setSql("like = like + 1").eq("id",id).update();
            if(isSuccess) {
                stringRedisTemplate.opsForValue().set("blog:liked2:" + userId +id, "add");
            }
        } else {
            Boolean isSuccess = update().setSql("like = like - 1").eq("id",id).update();
            if(isSuccess) {
                stringRedisTemplate.opsForValue().set("blog:liked2:" + userId +id, "remove");
            }
        }
        return Result.ok();
    }
    @Override
    public  Result queryBlogLikes(Long id) {
        // zrange key 0 4 返回查询，解析出用户id
        Set<String> set = stringRedisTemplate.opsForZSet().range("blog:liked1:" + id, 0 ,4);
        if(set == null || set.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        System.out.println(set);
        List<Long> userIds = set.stream().map(Long::valueOf).collect(Collectors.toList());
        // 根据id返回用户
        List<User> userDTOS = userService.listByIds(userIds).stream().map(user -> BeanUtil.copyProperties(user, User.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
    private void getResult(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
