package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeLists() {
        // 查询Redis里面是否有缓存
        String ShopTypeList = stringRedisTemplate.opsForValue().get("shopTypeList");
        if (StrUtil.isNotBlank(ShopTypeList)) {
            List<ShopType> shopTypes = JSONUtil.toList(ShopTypeList, ShopType.class);
            // bug处
            return Result.ok(shopTypes);
        }
        // 如果没有 则访问数据库
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        if(shopTypes == null) {
            return Result.fail("你要找的分类不存在");
        }
        stringRedisTemplate.opsForValue().set("shopTypeList", JSONUtil.toJsonStr(shopTypes));
        return Result.ok(shopTypes);
    }
}
