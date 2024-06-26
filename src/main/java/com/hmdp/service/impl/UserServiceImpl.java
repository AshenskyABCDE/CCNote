package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
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
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.ObjDoubleConsumer;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author ashensky
 * @since 2024-6-20
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 思路 校验手机号
        // 返回错误信息
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式有误");
        }
        // 生成验证码
        String Ncode = RandomUtil.randomNumbers(6);
        //为了防止其他业务也用，增加个前缀
        stringRedisTemplate.opsForValue().set("login:code:" + phone,Ncode,2, TimeUnit.MINUTES);

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
//        Object rightCode = session.getAttribute("code");
        Object rightCode = stringRedisTemplate.opsForValue().get("login:code:" + phone);
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
        // 保存用户信息到redis
        String token = UUID.randomUUID().toString(true);
        // 将User对象转为Hash 用一个随机的token作为key，用hash数据结构记载属性
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String,Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        //设置token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY,30,TimeUnit.MINUTES);
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        // 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 拼接key
        String keySuf = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = "sign:" + userId + keySuf;
        // 今天是本月的第几天
        int day = now.getDayOfMonth();
        // 写入redis SETBIT key offset
        stringRedisTemplate.opsForValue().setBit(key, day - 1, true);
        return Result.ok();
    }

    @Override
    public  Result signCount() {
        // 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 拼接key
        String keySuf = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = "sign:" + userId + keySuf;
        // 今天是本月的第几天
        int day = now.getDayOfMonth();
        // 获取本月截止今天为止的签到记录
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(day)).valueAt(0)
        );

        if(result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0) {
            return Result.ok(0);
        }
        System.out.println(key);
        int count = 0;
        while (true) {
            if((num & 1) == 0) {
                // 未签到
                break;
            } else {
                count ++;
            }
            num = num >> 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("Ccuser_" + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
