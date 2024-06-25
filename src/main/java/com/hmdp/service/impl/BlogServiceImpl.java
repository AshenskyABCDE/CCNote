package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
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
import java.util.List;

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
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "blog:liked1:" + blog.getId();
        // 判断是否点赞过
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        blog.setIsLike(BooleanUtil.isTrue(isMember));
    }

    @Override
    public  Result likeBlog(Long id) {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "blog:liked1:" + id;
        // 判断是否点赞过
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if(BooleanUtil.isFalse(isMember)) {
            // update tb_blog set like = like -1 where id = "id"
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id",id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
        } else {
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id",id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
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
    private void getResult(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
