package com.jack.mc.seckill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class RedisScriptConfig {

    /**
     * 扣减脚本。
     * <p>
     * 脚本用 ClassPathResource 从 lua 文件加载，而不是写成 Java 字符串：
     * 一是可读性，二是 IDEA 对 .lua 文件有语法高亮，改错能立刻看出来。
     * <p>
     * DefaultRedisScript 内部会缓存 SHA1，Spring Data Redis 执行时优先走 EVALSHA，
     * 不会每次都把脚本正文发给 Redis——只有脚本不存在时才会回退成 EVAL 重新加载。
     */
    @Bean
    public DefaultRedisScript<Long> seckillDeductScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/seckill_deduct.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public DefaultRedisScript<Long> seckillRollbackScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/seckill_rollback.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 孤儿扣减的原子认领。
     * <p>
     * 「判断是否超时」和「写认领标记」必须在同一个脚本里完成，
     * 否则两步之间记录可能已经被 confirmDeduct 清掉，
     * 兜底任务就会认领到一笔已经完成的扣减，然后去回滚一笔正常订单的库存。
     */
    @Bean
    public DefaultRedisScript<Long> seckillOrphanClaimScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/seckill_orphan_claim.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 确认扣减：检查认领标记 + 删除待确认记录，一次往返完成。
     */
    @Bean
    public DefaultRedisScript<Long> seckillConfirmDeductScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/seckill_confirm_deduct.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
