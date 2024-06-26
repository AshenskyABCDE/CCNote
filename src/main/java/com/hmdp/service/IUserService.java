package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.utils.RegexUtils;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author ashensky
 * @since 2024-6-20
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);
    Result login(LoginFormDTO loginFrom, HttpSession session);

    Result sign();

    Result signCount();
}
