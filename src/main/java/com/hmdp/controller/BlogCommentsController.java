package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.service.IBlogCommentsService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author ashensky
 * @since 2024-6-20
 */
@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {
    @Resource
    private IBlogCommentsService blogCommentsService;
    @PostMapping("/send")
    public Result sendComments(@RequestBody BlogComments comment) {
        return blogCommentsService.sendComments(comment);
    }

    @GetMapping("/page")
    public  Result finaAllCommentsByBlogId(@RequestParam(value = "current", defaultValue = "1") Integer current, Long blogId) {
        return blogCommentsService.finaAllCommentsByBlogId(current, blogId);
    }
}
