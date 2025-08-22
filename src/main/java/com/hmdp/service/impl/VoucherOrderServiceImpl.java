package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 初始化时加载脚本,避免每次释放锁时才加载造成大量I/O流
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    /**
     * 监听秒杀队列消息的方法
     * 使用RabbitListener注解来监听RabbitMQ中的消息
     * 这里绑定了一个名为"SecKill.queue"的队列到名为"SecKill.fanout"的fanout交换机上
     * Fanout交换机会将接收到的消息广播到所有绑定的队列中
     *
     * @param voucherOrder 接收到的订单对象，这是从队列中消费的消息内容
     * @throws InterruptedException 如果处理订单时被中断
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "SecKill.queue",durable = "true"),
            exchange = @Exchange(name = "SecKill.fanout",type = ExchangeTypes.FANOUT)
    ))
    public void listenSecKillQueueMessage(VoucherOrder voucherOrder) throws InterruptedException{
        handleVoucherOrder(voucherOrder);
    }

    //使用阻塞队列
    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while(true){
                try{
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e){
                    log.error("处理订单异常",e);
                }
            }
        }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3.获取锁
        boolean isLock = lock.tryLock();
        // 4.判断是否获取锁成功
        if(!isLock){
            //获取锁失败
            log.error("不允许重复下单");
            return;
        }
        //获取锁成功
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId){
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //2.判断结果是否为0  是否有抢券资格
        int r = result.intValue();
        if (r != 0){
            //2.1不为0,代表没有购买资格
            return Result.fail(r == 1 ? "库存不足":"不能重复下单");
        }
        //2.2为0,有购买资格,把下单信息保存到消息队列
        VoucherOrder voucherOrder = new VoucherOrder();
        //2.3 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //2.4 用户id
        voucherOrder.setUserId(userId);
        //2.5 代金券id
        voucherOrder.setVoucherId(voucherId);
        //2.6 放入阻塞队列
        //orderTasks.add(voucherOrder);
        //2.6 放入RabbitMQ中异步执行下单任务
        try {
            rabbitTemplate.convertAndSend("SecKill.fanout","SecKill.queue",voucherOrder);
        } catch (Exception e){
            log.error("执行下单任务失败,订单id{}",voucherOrder.getVoucherId());
        }
        //3. 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //4.返回订单id
        return Result.ok(orderId);
    }
    /**
     * 秒杀券抢购
     *
     * @param voucherOrder
     */
    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //4.一人一单
        Long userId = voucherOrder.getUserId();
        //4.1 查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //4.2 判断是否存在
        if (count > 0 ){
            //用户已经购买过了
            log.error("用户已经购买过一次了!");
            return;
        }
        //5.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0)
                .update();
        if (!success){
            //扣减失败
            log.error("库存不足,扣减失败");
            return;
        }
        //6.创建订单
        save(voucherOrder);
    }


/*    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始或结束
        if (voucher.getBeginTime().isAfter(LocalDateTime.now()) && voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("不在秒杀活动时间范围!!!");
        }
        //3.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        //synchronized (userId.toString().intern()) {
        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if(!isLock){
            //获取锁失败,返回错误或重试
            return Result.fail("不允许重复下单!");
        }
        //获取锁成功
        try {
            //获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }
        //}
    }*/
}
