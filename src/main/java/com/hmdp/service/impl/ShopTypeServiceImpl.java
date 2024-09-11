package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.servlet.GenericServlet;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    StringRedisTemplate stringRedisTemplate;

    /**
     * 将主页中的商铺类型进行缓存，（不经常更新的数据）
     * @return
     */
    @Override
    public Result queryByList() {
        // 1.从Redis查询数据
        Long size = stringRedisTemplate.opsForList().size(CACHE_SHOP_TYPE_KEY);
        // 2.判断列表是否存在,存在则直接返回
        if (size != 0) {
            List<String> typeList = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, size);
        // 3.将List<String>转为List<ShopType>
            List<ShopType> shopTypes = typeList.stream().map(s -> JSONUtil.toBean(s, ShopType.class)).collect(Collectors.toList());
            return Result.ok(shopTypes);
        }
        // 4.不存在则直接访问数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if(typeList==null && typeList.isEmpty()){
            return Result.fail("查询失败，联系管理员");
        }
        // 5.数据库有,同步到Redis
        // 6.将List<ShopType>转为List<String>
        List<String> typeListToStrings = typeList.stream().map(shopType -> JSONUtil.toJsonStr(shopType)).collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY, typeListToStrings);
        return Result.ok(typeList);
    }
}
