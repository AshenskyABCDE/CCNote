package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.function.ObjDoubleConsumer;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 思路 校验手机号
        // 返回错误信息
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式有误");
        }
        // 生成验证码
        String Ncode = RandomUtil.randomNumbers(6);
        session.setAttribute("code", Ncode);
        log.debug("发送短信验证码成功，验证码:{} " + Ncode);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginFrom, HttpSession session) {
        // 首先要检验手机号是否正确
        String phone = loginFrom.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式有误");
        }
        // 查看验证码是否正确
        Object rightCode = session.getAttribute("code");
        String code = loginFrom.getCode();
        // 为空 或者 验证码错误
        if(code == null) {
            return Result.fail("验证码为空");
        }
        if(!rightCode.toString().equals(code)) {
            return Result.fail("输入验证码错误");
        }
        // 登录之后的业务逻辑 : 查看手机号是否存在
        // 注解有tablename 所以知道是tb_user这个表
        User user = query().eq("phone",phone).one();

        if(user == null) {
            user = createUserWithPhone(phone);
        }
        // 为了防止重要信息泄露，应存放UserDTO类型的
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("Ccuser_" + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
