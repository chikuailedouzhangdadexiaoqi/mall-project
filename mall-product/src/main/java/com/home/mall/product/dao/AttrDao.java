package com.home.mall.product.dao;

import com.home.mall.product.entity.AttrEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品属性
 * 
 * @author lyq
 * @email 1525761478@qq.com
 * @date 2023-01-09 19:35:24
 */
@Mapper
public interface AttrDao extends BaseMapper<AttrEntity> {

    List<Long> getAllCanSearchIdsByAttrIds(@Param("attrIds") List<Long> attrIds);
}
