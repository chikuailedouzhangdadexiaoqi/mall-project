package com.home.mall.product.service.impl;

import com.alibaba.fastjson.TypeReference;
import com.home.common.constant.ProductConstant;
import com.home.common.to.HasStockTo;
import com.home.common.to.SkuReductionTo;
import com.home.common.to.SpuBoundTo;
import com.home.common.to.es.SkuEsModel;
import com.home.common.utils.R;
import com.home.mall.product.entity.*;
import com.home.mall.product.feign.CouponFeignService;
import com.home.mall.product.feign.SearchFeignService;
import com.home.mall.product.feign.WareFeignService;
import com.home.mall.product.service.*;
import com.home.mall.product.vo.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.home.common.utils.PageUtils;
import com.home.common.utils.Query;

import com.home.mall.product.dao.SpuInfoDao;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

@Slf4j
@Service("spuInfoService")
public class SpuInfoServiceImpl extends ServiceImpl<SpuInfoDao, SpuInfoEntity> implements SpuInfoService {
    @Autowired
    SpuInfoDescService spuInfoDescService;

    @Autowired
    SpuImagesService imagesService;

    @Autowired
    AttrService attrService;

    @Autowired
    ProductAttrValueService attrValueService;

    @Autowired
    SkuInfoService skuInfoService;
    @Autowired
    SkuImagesService skuImagesService;

    @Autowired
    SkuSaleAttrValueService skuSaleAttrValueService;

    @Autowired
    CouponFeignService couponFeignService;
    @Resource
    BrandService brandService;

    @Resource
    CategoryService categoryService;

    @Resource
    private ProductAttrValueService productAttrValueService;

    @Resource
    private WareFeignService wareFeignService;
    @Resource
    private SearchFeignService searchFeignService;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<SpuInfoEntity> page = this.page(
                new Query<SpuInfoEntity>().getPage(params),
                new QueryWrapper<SpuInfoEntity>()
        );

        return new PageUtils(page);
    }

    /**
     * //TODO ??????????????????
     *
     * @param vo
     */
    @Override
//    @GlobalTransactional
    @Transactional
    public void saveSpuInfo(SpuSaveVo vo) {

        //1?????????spu???????????? pms_spu_info
        SpuInfoEntity infoEntity = new SpuInfoEntity();
        BeanUtils.copyProperties(vo, infoEntity);
        infoEntity.setCreateTime(new Date());
        infoEntity.setUpdateTime(new Date());
        this.saveBaseSpuInfo(infoEntity);

        //2?????????Spu??????????????? pms_spu_info_desc
        List<String> decript = vo.getDecript();
        SpuInfoDescEntity descEntity = new SpuInfoDescEntity();
        descEntity.setSpuId(infoEntity.getId());
        descEntity.setDecript(String.join(",", decript));
        spuInfoDescService.saveSpuInfoDesc(descEntity);


        //3?????????spu???????????? pms_spu_images
        List<String> images = vo.getImages();
        imagesService.saveImages(infoEntity.getId(), images);


        //4?????????spu???????????????;pms_product_attr_value
        List<BaseAttrs> baseAttrs = vo.getBaseAttrs();
        List<ProductAttrValueEntity> collect = baseAttrs.stream().map(attr -> {
            ProductAttrValueEntity valueEntity = new ProductAttrValueEntity();
            valueEntity.setAttrId(attr.getAttrId());
            if (attr.getAttrId() != null) {
                AttrEntity id = attrService.getById(attr.getAttrId());
                if (id != null) {
                    if (!StringUtils.isEmpty(id.getAttrName()))
                        valueEntity.setAttrName(id.getAttrName());
                }
            }
            valueEntity.setAttrValue(attr.getAttrValues());
            valueEntity.setQuickShow(attr.getShowDesc());
            valueEntity.setSpuId(infoEntity.getId());

            return valueEntity;
        }).collect(Collectors.toList());
        attrValueService.saveProductAttr(collect);
        //5?????????spu??????????????????gulimall_sms->sms_spu_bounds
        Bounds bounds = vo.getBounds();
        SpuBoundTo spuBoundTo = new SpuBoundTo();
        BeanUtils.copyProperties(bounds, spuBoundTo);
        spuBoundTo.setSpuId(infoEntity.getId());
        R r = couponFeignService.saveSpuBounds(spuBoundTo);
//        if(r.getCode() != 0){
//            log.error("????????????spu??????????????????");
//        }


        //5???????????????spu???????????????sku?????????

        List<Skus> skus = vo.getSkus();
        if (skus != null && skus.size() > 0) {
            skus.forEach(item -> {
                String defaultImg = "";
                for (Images image : item.getImages()) {
                    if (image.getDefaultImg() == 1) {
                        defaultImg = image.getImgUrl();
                    }
                }
                //    private String skuName;
                //    private BigDecimal price;
                //    private String skuTitle;
                //    private String skuSubtitle;
                SkuInfoEntity skuInfoEntity = new SkuInfoEntity();
                BeanUtils.copyProperties(item, skuInfoEntity);
                skuInfoEntity.setBrandId(infoEntity.getBrandId());
                skuInfoEntity.setCatalogId(infoEntity.getCatalogId());
                skuInfoEntity.setSaleCount(0L);
                skuInfoEntity.setSpuId(infoEntity.getId());
                skuInfoEntity.setSkuDefaultImg(defaultImg);
                //5.1??????sku??????????????????pms_sku_info
                skuInfoService.saveSkuInfo(skuInfoEntity);

                Long skuId = skuInfoEntity.getSkuId();

                List<SkuImagesEntity> imagesEntities = item.getImages().stream().map(img -> {
                    SkuImagesEntity skuImagesEntity = new SkuImagesEntity();
                    skuImagesEntity.setSkuId(skuId);
                    skuImagesEntity.setImgUrl(img.getImgUrl());
                    skuImagesEntity.setDefaultImg(img.getDefaultImg());
                    return skuImagesEntity;
                }).filter(entity -> {
                    //??????true???????????????false????????????
                    return !StringUtils.isEmpty(entity.getImgUrl());
                }).collect(Collectors.toList());
                //5.2??????sku??????????????????pms_sku_image
                skuImagesService.saveBatch(imagesEntities);
                //TODO ?????????????????????????????????

                List<Attr> attr = item.getAttr();
                List<SkuSaleAttrValueEntity> skuSaleAttrValueEntities = attr.stream().map(a -> {
                    SkuSaleAttrValueEntity attrValueEntity = new SkuSaleAttrValueEntity();
                    BeanUtils.copyProperties(a, attrValueEntity);
                    attrValueEntity.setSkuId(skuId);

                    return attrValueEntity;
                }).collect(Collectors.toList());
                //5.3??????sku????????????????????????pms_sku_sale_attr_value
                skuSaleAttrValueService.saveBatch(skuSaleAttrValueEntities);

                // //5.4??????sku??????????????????????????????gulimall_sms->sms_sku_ladder\sms_sku_full_reduction\sms_member_price
                SkuReductionTo skuReductionTo = new SkuReductionTo();
                BeanUtils.copyProperties(item, skuReductionTo);
                skuReductionTo.setSkuId(skuId);
                if (skuReductionTo.getFullCount() > 0 || skuReductionTo.getFullPrice().compareTo(new BigDecimal("0")) == 1) {
                    R r1 = couponFeignService.saveSkuReduction(skuReductionTo);
//                    if(r1.getCode() != 0){
//                        log.error("????????????sku??????????????????");
//                    }
                }


            });
        }


    }

    @Override
    public void saveBaseSpuInfo(SpuInfoEntity spuInfoEntity) {
        this.save(spuInfoEntity);
    }

    /**
     * ??????spu??????
     *
     * @param params
     * @return
     */
    @Override
    public PageUtils querySpuInfo(Map<String, Object> params) {
        QueryWrapper<SpuInfoEntity> queryWrapper = new QueryWrapper<>();
        String key = (String) params.get("key");
        if (!StringUtils.isEmpty(key)) { //???????????????????????????
            queryWrapper.and((wrapper) -> {
                wrapper.eq("id", key).or().like("spu_name", key);
            });
        }

        String catelogId = (String) params.get("catelogId");
        if (!StringUtils.isEmpty(catelogId) && !"0".equalsIgnoreCase(catelogId)) { //??????????????????id????????????
            queryWrapper.eq("catalog_id", catelogId);
        }
        String brandId = (String) params.get("brandId");
        if (!StringUtils.isEmpty(brandId) && !"0".equalsIgnoreCase(brandId)) { //?????????????????????id??????
            queryWrapper.eq("brandId", brandId);
        }
        String status = (String) params.get("status");
        if (!StringUtils.isEmpty(status)) { //???????????????????????????
            queryWrapper.eq("publish_status", status);
        }
        IPage<SpuInfoEntity> page = this.page(
                new Query<SpuInfoEntity>().getPage(params),
                queryWrapper
        );
        return new PageUtils(page);
    }

    /**
     * ??????????????????
     *
     * @param spuId
     */
    @Override
    public void up(Long spuId)  {
        //??????????????????es????????????
        //1.??????spuId?????????spu??????????????????sku??????
        List<SkuInfoEntity> skuInfoEntities = skuInfoService.getAllSkuInfoBySpuId(spuId);
        //??????spuId?????????????????????
        List<ProductAttrValueEntity> attrValueEntities=productAttrValueService.getAttrIdsBySpuId(spuId);
        //?????????????????????????????????id
        List<Long> attrIds = attrValueEntities.stream().map(item -> {
            return item.getAttrId();
        }).collect(Collectors.toList());
        //??????????????????????????????id?????????????????????????????????????????????id??????
        List<Long> canSearchIds=attrService.getAllCanSearchIdsByAttrIds(attrIds);
        Set<Long> ids=new HashSet<>(canSearchIds);
        List<SkuEsModel.Attr> attrs = attrValueEntities.stream().filter(item -> {
            return ids.contains(item.getAttrId());
        }).map(item -> {
            SkuEsModel.Attr attr = new SkuEsModel.Attr();
            BeanUtils.copyProperties(item, attr);
            return attr;
        }).collect(Collectors.toList());
        //TODO ???????????????????????????????????????????????????
        List<Long> skuIds = skuInfoEntities.stream().map(SkuInfoEntity::getSkuId).collect(Collectors.toList());
        Map<Long,Boolean> stockMap=null;
        try{
            R r = wareFeignService.hasStock(skuIds);
            TypeReference<List<HasStockTo>> typeReference = new TypeReference<List<HasStockTo>>() {
            };
            stockMap =r.getData(typeReference).stream().collect(Collectors.toMap(HasStockTo::getSkuId,HasStockTo::getHasStock));
        }catch (Exception e){
            log.error("??????????????????",e.getCause(),e.getMessage());
        }

        Map<Long, Boolean> finalStockMap = stockMap;
        List<SkuEsModel> esModels = skuInfoEntities.stream().map(sku -> {
            SkuEsModel esModel = new SkuEsModel();
            BeanUtils.copyProperties(sku, esModel);
            esModel.setSkuPrice(sku.getPrice());
            esModel.setSkuImg(sku.getSkuDefaultImg());
            if(finalStockMap.get(sku.getSkuId())==null){
                esModel.setHasStock(false);
            }else{
                esModel.setHasStock(finalStockMap.get(sku.getSkuId()));
            }
            esModel.setHotScore(0L);
            //??????brandId???????????????????????????????????????????????????????????????
            BrandEntity brand = brandService.getById(esModel.getBrandId());
            esModel.setBrandName(brand.getName());
            esModel.setBrandImg(brand.getLogo());
            //???????????????????????????
            CategoryEntity category = categoryService.getById(esModel.getCatalogId());
            esModel.setCatalogName(category.getName());
            //??????Attrs??????
            esModel.setAttrs(attrs);
            return esModel;
        }).collect(Collectors.toList());

        //TODO ?????????es??? ,????????????search??????
        try {
            R r = searchFeignService.skuSave(esModels);
            if(r.getCode()==0) {
                log.info("?????????????????????es???????????????");
                //??????spuinfo??????????????????
                baseMapper.updateSpuInfoStatus(spuId, ProductConstant.StatusEnum.UP.getCode());
            }else{
                log.error("???????????????????????????es???????????????");
            }
        }catch (Exception e){
            log.error("???????????????es?????????????????????");
        }


    }

    @Override
    public SpuInfoRespVo getSpuInfo(Long skuId) {
        SkuInfoEntity skuInfo = skuInfoService.getById(skuId);
        Long spuId = skuInfo.getSpuId();
        SpuInfoEntity spuInfoEntity = this.baseMapper.selectById(spuId);
        SpuBoundsEntity spuBounds = couponFeignService.getSpuBounds(spuId);
        SpuInfoRespVo spuInfoRespVo = new SpuInfoRespVo();
        spuInfoRespVo.setId(spuId);
        spuInfoRespVo.setSpuName(spuInfoEntity.getSpuName());
        spuInfoRespVo.setBrandId(spuInfoEntity.getBrandId());
        spuInfoRespVo.setCatalogId(spuInfoEntity.getCatalogId());
        spuInfoRespVo.setBuyBounds(spuBounds.getBuyBounds());
        spuInfoRespVo.setGrowBounds(spuBounds.getGrowBounds());
        return spuInfoRespVo;
    }

}