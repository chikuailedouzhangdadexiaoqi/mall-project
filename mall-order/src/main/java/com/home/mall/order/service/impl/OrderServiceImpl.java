package com.home.mall.order.service.impl;

import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.home.common.constant.ProductConstant;
import com.home.common.exception.WareException;
import com.home.common.utils.R;
import com.home.common.vo.MemberRespVo;
import com.home.mall.order.constant.OrderConstant;
import com.home.mall.order.constant.OrderStatusEnum;
import com.home.mall.order.entity.OrderItemEntity;
import com.home.mall.order.feign.CartFeignService;
import com.home.mall.order.feign.MemberFeignService;
import com.home.mall.order.feign.ProductFeignService;
import com.home.mall.order.feign.WmsFeignService;
import com.home.mall.order.interceptor.LoginInterceptor;
import com.home.mall.order.service.OrderItemService;
import com.home.mall.order.to.OrderCreateTo;
import com.home.mall.order.vo.*;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.home.common.utils.PageUtils;
import com.home.common.utils.Query;

import com.home.mall.order.dao.OrderDao;
import com.home.mall.order.entity.OrderEntity;
import com.home.mall.order.service.OrderService;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import javax.annotation.Resource;


@Service("orderService")
public class OrderServiceImpl extends ServiceImpl<OrderDao, OrderEntity> implements OrderService {
    //???????????????????????????????????????ThreadLocal????????????????????????????????????????????????threadLocal?????????????????????????????????
    private ThreadLocal<OrderSubmitVo> threadLocal = new ThreadLocal<OrderSubmitVo>();
    @Resource
    private MemberFeignService memberFeignService;

    @Resource
    private CartFeignService cartFeignService;
    @Autowired
    private ThreadPoolExecutor executor;

    @Autowired
    private WmsFeignService wmsFeignService;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private ProductFeignService productFeignService;
    @Autowired
    private OrderItemService orderItemService;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<OrderEntity> page = this.page(
                new Query<OrderEntity>().getPage(params),
                new QueryWrapper<OrderEntity>()
        );

        return new PageUtils(page);
    }

    @Override
    public OrderConfirmVo orderConfirm() {
        OrderConfirmVo confirmVo = new OrderConfirmVo();
        MemberRespVo memberRespVo = LoginInterceptor.loginUser.get();
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        CompletableFuture<Void> addressTask = CompletableFuture.runAsync(() -> {
            RequestContextHolder.setRequestAttributes(requestAttributes);
            List<MemberAddressVo> address = memberFeignService.address(memberRespVo.getId());
            confirmVo.setAddress(address);
        }, executor);
        CompletableFuture<Void> cartItemTask = CompletableFuture.runAsync(() -> {
            RequestContextHolder.setRequestAttributes(requestAttributes);
            List<OrderItemVo> items = cartFeignService.getCartItems();
            confirmVo.setItems(items);
        }, executor).thenRunAsync(() -> {
            List<OrderItemVo> items = confirmVo.getItems();
            List<Long> skuIds = items.stream().map(item -> item.getSkuId()).collect(Collectors.toList());
            R hasStock = wmsFeignService.hasStock(skuIds);
            List<SkuStockVo> stocks = hasStock.getData(new TypeReference<List<SkuStockVo>>() {
            });
            if (stocks != null) {
                Map<Long, Boolean> collect = stocks.stream().collect(Collectors.toMap(SkuStockVo::getSkuId, SkuStockVo::getHasStock));
                confirmVo.setStocks(collect);
            }

        }, executor);
        confirmVo.setIntegration(memberRespVo.getIntegration());
        //????????????????????????
        String token = UUID.randomUUID().toString().replace("-", "");
        //?????????redis???
        redisTemplate.opsForValue().set(OrderConstant.USER_ORDER_PREFIX_TOKEN + memberRespVo.getId(), token, 30, TimeUnit.MINUTES);
        //???????????????????????????????????????????????????
        confirmVo.setOrderToken(token);
        try {
            CompletableFuture.allOf(addressTask, cartItemTask).get();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }

        return confirmVo;
    }

    @Override
//    @GlobalTransactional
    @Transactional
    public OrderSubmitRespVo submitOrder(OrderSubmitVo vo) {

        OrderSubmitRespVo orderSubmitRespVo = new OrderSubmitRespVo();
        //?????????????????????
        MemberRespVo memberRespVo = LoginInterceptor.loginUser.get();
        //??????????????????orderToken
        String voOrderToken = vo.getOrderToken();
        //??????redis???????????????????????????????????????????????????????????????????????????????????????lua??????
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
        //redis???????????????api?????????????????????????????????
        Long res = redisTemplate.execute(new DefaultRedisScript<Long>(script,Long.class), Arrays.asList(OrderConstant.USER_ORDER_PREFIX_TOKEN + memberRespVo.getId()), voOrderToken);
        if (res == 0L) {
            //????????????????????????
            orderSubmitRespVo.setCode(1);//????????????????????????
            return orderSubmitRespVo;
        } else {
            //??????????????????
            //?????????????????????
            OrderCreateTo order = orderCreate();

            //??????
            BigDecimal voPayPrice = vo.getPayPrice();
            BigDecimal payAmount = order.getOrder().getPayAmount();
            if(Math.abs((voPayPrice.subtract(payAmount)).doubleValue())<0.01){
                //????????????
                //TODO ??????????????????????????????
                saveOrder(order);
                //???????????????????????????????????????
                WareSkuLock wareSkuLock=new WareSkuLock();
                wareSkuLock.setOrderSn(order.getOrder().getOrderSn());
                List<OrderItemVo> locks = order.getOrderItems().stream().map(item -> {
                    OrderItemVo orderItemVo = new OrderItemVo();
                    orderItemVo.setSkuId(item.getSkuId());
                    orderItemVo.setTitle(item.getSkuName());
                    orderItemVo.setCount(item.getSkuQuantity());
                    return orderItemVo;
                }).collect(Collectors.toList());
                wareSkuLock.setLocks(locks);
                R r = wmsFeignService.lockOrder(wareSkuLock);
                if(r.getCode()==0){
                    int n=10/0;
                    //??????????????????
                    //????????????
                    orderSubmitRespVo.setOrder(order.getOrder());
                    orderSubmitRespVo.setCode(0);
                    return orderSubmitRespVo;
                }else{
                    String msg = (String) r.get("msg");
                    throw new  WareException(msg);
                }
            }else{
                //????????????
                orderSubmitRespVo.setCode(2);//????????????
                return orderSubmitRespVo;
            }
        }
    }

    @Override
    public OrderEntity getOrderStaus(String orderSn) {
        QueryWrapper<OrderEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("order_sn",orderSn);
        OrderEntity orderEntity = this.baseMapper.selectOne(queryWrapper);
        return orderEntity;
    }

    private void saveOrder(OrderCreateTo order) {
        OrderEntity orderEntity = order.getOrder();
        List<OrderItemEntity> orderItems = order.getOrderItems();
        this.save(orderEntity);
        for (OrderItemEntity orderItem : orderItems) {
            orderItemService.save(orderItem);
        }
    }

    private OrderCreateTo orderCreate() {
        OrderCreateTo orderCreateTo = new OrderCreateTo();
        OrderSubmitVo orderSubmitVo = threadLocal.get();
        //??????????????????
        OrderEntity orderEntity=buildOrder(orderSubmitVo);
        orderCreateTo.setOrder(orderEntity);

        //?????????????????????
        List<OrderItemEntity> orderItems=buildOrderItems();
        orderCreateTo.setOrderItems(orderItems);
        computer(orderEntity,orderItems);

        return orderCreateTo;
    }

    private void computer(OrderEntity orderEntity, List<OrderItemEntity> orderItems) {
        BigDecimal total=new BigDecimal("0.0");
        BigDecimal integrationAmount=new BigDecimal("0.0");//??????????????????
        BigDecimal couponAmount=new BigDecimal("0.0");//?????????????????????
        BigDecimal integration=new BigDecimal("0.0");//???????????????
        BigDecimal growth=new BigDecimal("0.0");//??????????????????
        for (OrderItemEntity orderItem : orderItems) {
            BigDecimal realAmount = orderItem.getRealAmount();
            total=total.add(realAmount);
            integrationAmount=integrationAmount.add(orderItem.getIntegrationAmount());
            couponAmount=couponAmount.add(orderItem.getCouponAmount());
            integration=new BigDecimal(orderItem.getGiftIntegration().toString()).multiply(new BigDecimal(orderItem.getSkuQuantity().toString()));
            growth=new BigDecimal(orderItem.getGiftGrowth().toString()).multiply(new BigDecimal(orderItem.getSkuQuantity().toString()));
        }
        orderEntity.setTotalAmount(total); //???????????????????????????
        total=total.subtract(integrationAmount).subtract(couponAmount);
        orderEntity.setPayAmount(total);//???????????????????????????
        orderEntity.setIntegrationAmount(integrationAmount);//?????????????????????????????????
        orderEntity.setCouponAmount(couponAmount);//????????????????????????????????????
        orderEntity.setIntegration(integration.intValue());//?????????????????????
        orderEntity.setGrowth(growth.intValue());//????????????????????????
        orderEntity.setDeleteStatus(0);//???????????????????????????
    }

    //?????????????????????
    private List<OrderItemEntity> buildOrderItems() {
        List<OrderItemVo> cartItems = cartFeignService.getCartItems();
        if(cartItems!=null && cartItems.size()>0){
            List<OrderItemEntity> orderItemEntities = cartItems.stream().map(item -> {
                OrderItemEntity orderItemEntity = buildOrderItem(item);
                return orderItemEntity;
            }).collect(Collectors.toList());
            return orderItemEntities;
        }
        return null;
    }

    //?????????????????????
    private OrderItemEntity buildOrderItem(OrderItemVo item) {
        OrderItemEntity orderItemEntity = new OrderItemEntity();
        orderItemEntity.setSkuId(item.getSkuId());
        orderItemEntity.setSkuName(item.getTitle());
        orderItemEntity.setSkuPic(item.getImage());
        orderItemEntity.setSkuPrice(item.getPrice());
        orderItemEntity.setSkuQuantity(item.getCount());
        List<String> skuAttrs = item.getSkuAttr();
        String skuAttr = StringUtils.collectionToDelimitedString(skuAttrs, ";");//????????????????????????????????????????????????????????????
        orderItemEntity.setSkuAttrsVals(skuAttr);
        //?????????????????????????????????skuId??????spu??????
        SpuInfoRespVo spuInfoRespVo = productFeignService.getSpuInfo(item.getSkuId());
        orderItemEntity.setSpuId(spuInfoRespVo.getId());
        orderItemEntity.setSpuName(spuInfoRespVo.getSpuName());
        orderItemEntity.setSpuBrand(spuInfoRespVo.getBrandId().toString());
        orderItemEntity.setCategoryId(spuInfoRespVo.getCatalogId());
        orderItemEntity.setGiftIntegration(spuInfoRespVo.getBuyBounds().intValue());
        orderItemEntity.setGiftGrowth(spuInfoRespVo.getGrowBounds().intValue());
        orderItemEntity.setPromotionAmount(new BigDecimal("0.0"));
        orderItemEntity.setCouponAmount(new BigDecimal("0.0"));
        orderItemEntity.setIntegrationAmount(new BigDecimal("0.0"));
        BigDecimal total=new BigDecimal("0.0");
        BigDecimal count = new BigDecimal(orderItemEntity.getSkuQuantity().toString());
        BigDecimal price = orderItemEntity.getSkuPrice();
        total=count.multiply(price);
        total=total.subtract(orderItemEntity.getIntegrationAmount()).subtract(orderItemEntity.getPromotionAmount()).subtract(orderItemEntity.getIntegrationAmount());
        orderItemEntity.setRealAmount(total);//???????????????????????????
        return orderItemEntity;
    }

    private OrderEntity buildOrder(OrderSubmitVo orderSubmitVo) {
        MemberRespVo memberRespVo = LoginInterceptor.loginUser.get();
        //??????????????????
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setMemberId(memberRespVo.getId());
        //???????????????,?????????????????????????????????????????????????????????????????????id??????????????????????????????
        String orderSn = IdWorker.getTimeId();
        orderEntity.setOrderSn(orderSn);
//        Long addrId = orderSubmitVo.getAddrId();//??????????????????????????????????????????id
        MemberReceiveAddressEntity receiveMember = memberFeignService.getReceiveMember(1L);


        orderEntity.setReceiverName(receiveMember.getName());//??????????????????
        orderEntity.setReceiverPhone(receiveMember.getPhone());//?????????????????????
        orderEntity.setReceiverPostCode(receiveMember.getPostCode());//??????????????????
        orderEntity.setReceiverProvince(receiveMember.getProvince());//??????????????????
        orderEntity.setReceiverDetailAddress(receiveMember.getDetailAddress());//?????????????????????
        orderEntity.setReceiverRegion(receiveMember.getRegion());//????????????
        orderEntity.setReceiverCity(receiveMember.getCity());//???????????????
        orderEntity.setStatus(OrderStatusEnum.CREATE_NEW.getCode());//????????????
        orderEntity.setAutoConfirmDay(7);//??????????????????
        orderEntity.setFreightAmount(new BigDecimal("0.0"));
        return orderEntity;
    }


}