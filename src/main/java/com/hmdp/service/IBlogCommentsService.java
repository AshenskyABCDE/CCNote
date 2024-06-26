package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author ashensky
 * @since 2024-6-20
 */
public interface IBlogCommentsService extends IService<BlogComments> {

    Result sendComments(BlogComments blog);


    Result finaAllCommentsByBlogId(@RequestParam(value = "current", defaultValue = "1") Integer current, Long blogId);
}
