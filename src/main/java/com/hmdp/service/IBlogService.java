package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author ashensky
 * @since 2024-6-20
 */
public interface IBlogService extends IService<Blog> {

    Result queryHotBlog(Integer current);

    Result queryBlogByid(Long id);

    Result likeBlog(Long id);

    // 直接用 redis 判断是够点赞过的方法
    Result likeBlog2(Long id);
}
