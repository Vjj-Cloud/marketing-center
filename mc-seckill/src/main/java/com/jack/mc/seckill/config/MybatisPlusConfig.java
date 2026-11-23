package com.jack.mc.seckill.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件。
 * <p>
 * 两个插件缺一不可：
 * - 分页：不加的话 Page 参数会被静默忽略，返回全表数据，接口「看起来正常」但数据是错的；
 * - 乐观锁：@Version 字段只有配合这个插件才生效，否则版本号永远不会变，
 *   状态并发流转时后写的直接覆盖先写的，且不报错。
 * 这两个都是「不报错但结果错」的坑，比抛异常更难查。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
