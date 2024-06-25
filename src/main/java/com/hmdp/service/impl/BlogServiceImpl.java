package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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

    @Resource
    private IFollowService followService;
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
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文 save(blog);
        boolean isSuccess = save(blog);
        if(!isSuccess) {
            return Result.fail("笔记发布失败");
        }
        // 查询笔记作者的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 推送笔记给粉丝
        for(Follow follow : follows) {
            Long userId = follow.getUserId();
            String key = "feed:" + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }
    @Override
    public  Result queryBlogOfFollow(Long max, Integer offset) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = "feed:" + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        if(typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 解析数据 blogId， timestamp
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for(ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if(time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        // 通过id查询blog
        String idStr = StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id",ids).last("ORDER BY FIELD(id," + idStr+")").list();

        for (Blog blog : blogs) {
            // 查询blog的用户
            getResult(blog);
            // 查询blog是否被点赞
            isBlogLiked(blog);
        }
        // 封装并返回

        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }
    private void getResult(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
