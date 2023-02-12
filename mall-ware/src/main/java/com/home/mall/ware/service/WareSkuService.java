package com.home.mall.ware.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.home.common.to.HasStockTo;
import com.home.common.utils.PageUtils;
import com.home.mall.ware.entity.WareSkuEntity;

import java.util.List;
import java.util.Map;

/**
 * 商品库存
 *
 * @author lyq
 * @email 1525761478@qq.com
 * @date 2023-01-09 23:15:16
 */
public interface WareSkuService extends IService<WareSkuEntity> {

    PageUtils queryPage(Map<String, Object> params);

    void updateStock(Long skuId, Long wareId, Integer skuNum);

    List<HasStockTo> hasStock(List<Long> skuIds);
}
