package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.api.R;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestParam;

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
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendComments(BlogComments blogComments) {
        // 等待前端传给评论内容 相关 而发送的部分交给前端 这里我实现的前端并不是很好，我将其后端修改一下这个bug
        blogComments.setAnswerId(UserHolder.getUser().getId());
        Boolean isSuccess = save(blogComments);
        if(!isSuccess) {
            return Result.fail("服务器发生了异常");
        }
        Long blogId = blogComments.getBlogId();
        if(blogId == null) {
            return Result.fail("评论的帖子发生了异常");
        }
        // 通过访问redis 来缓解查询每页数据库的数据
        stringRedisTemplate.opsForValue().set("blogComments:" + blogId , "1");
        return Result.ok();
    }



    @Override
    public Result finaAllCommentsByBlogId(@RequestParam(value = "current", defaultValue = "1") Integer current, Long blogId) {
        String value = stringRedisTemplate.opsForValue().get("blogComments:" + blogId);
        if(!value.equals("1")) {
            return Result.fail("这个帖子没有被人评论过");
        }
        // MyBatis-Plus 进行分页查询
        Page<BlogComments> page = query()
                .eq("blog_id", blogId)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        List<BlogComments> list = page.getRecords();
        if(list == null || list.isEmpty()) {
            return Result.fail("获取评论列表失败");
        }
        return Result.ok(list);
    }

}
