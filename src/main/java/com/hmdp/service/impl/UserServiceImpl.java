package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.entity.ShopType;
import com.hmdp.dto.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号码格式有问题,请重试");
        }
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_USER_TTL, TimeUnit.MINUTES);
        session.setAttribute("code",code);
        log.debug("验证码发送成功，为:{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.提交手机号和验证码
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式有误");
        }
        // 从Redis获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();
        //2.校验验证码
        if(cacheCode==null || !cacheCode.equals(code)){
            return Result.fail("验证码有误,请重新注册");
        }

        //3.根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //4.判断用户存在，存在即登录成功，否则创建新用户
        //4.1 保存用户信息到Redis中
        //4.2 随机生成token，作为登录令牌
        String token = UUID.randomUUID(true).toString();
        System.out.println(token);
        if(user==null)
           user = createUserWithPhone(phone);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //4.3 将用户对象转换为Hash
        // hutool包下的转换类实现了自定义转换方式，CopyOptions
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>() ,
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fileName , fileValue)-> fileValue.toString()));
        String tokenKey = LOGIN_USER_KEY+token;
        //4.4 存储进Redis
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //4.5 设置有效.
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //4.6 返回token给前端
        return Result.ok(token);
    }
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }


}
