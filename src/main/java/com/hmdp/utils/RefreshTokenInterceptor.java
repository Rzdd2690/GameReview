package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;
import static com.hmdp.utils.UserHolder.saveUser;

/**
 * 多弄一个拦截器的原因是：
 * 场景：我们给每个登录的人是redis是有过期时间的，但是如果一个人他一直在用我们的接口，自然需要延长过期时间
 * 那么如果是之前的，在拦截器的时候，延长redis过期时间，我们就会有一个问题，用户如果走的接口是没有被拦截器拦截，
 * 即不需要登录就能实现的接口，自然就不会经过拦截器，自然就不能延长redis的过期时间，这显然不是我们需要的
 * 因此我们新加一个拦截器，他的作用就是无论什么请求，都拦截一下，并且无论是否登录（都放行给下一个拦截器进行判断）
 * 同时在拦截的同时，如果用户存在（登录情况），则刷新redis的过期时间。
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;

    // 注意，这里不能用自动注入@Resource 等，因为该属性存在的类是我们自己手动新建的，没有放到Bean容器管理，
    // 即这个类一开始不会被自动创建，我们只能手写构造方法，然后谁用他，在谁那自动注入
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取请求头中的token
        String token = request.getHeader("authorization");
 // 如果没有token，直接放行，让下一个拦截器进行拦截
        if (StrUtil.isBlank(token)){
            return true;
        }
        //2.基于token获取用户
        //返回Hash中value的一个键值字段，用get，返回key对应的全部value用entries
        String tokenKey = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        //3.判断用户是否存在
// 用户不存在，直接放行，给下一个拦截器进行放行
        if(userMap.isEmpty()){
            return true;
        }
// 用户存在，则转换为UserDTO（脱敏），并保存到ThreadLocal中，下一个拦截器通过判断ThreadLocal中有无数据，可以判断这个请求是否需要放行
        //将查询到的Hash数据转换为UserDTO数据
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO() ,false);
        //5.存在则保存到ThreadLocal中
        saveUser(userDTO);
// 最后别忘了刷新token有效期，这也是我们设置两个拦截器的意义，
// 第一个拦截器是拦截所有的请求，并把当前token的信息保存到Threadlocal，同时刷新有效期
        // 刷新token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
