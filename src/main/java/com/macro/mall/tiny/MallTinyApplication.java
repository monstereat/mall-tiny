package com.macro.mall.tiny;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用启动类（整个应用的入口）
 *
 * <p>运行 main 方法后，Spring Boot 会：
 * <ol>
 *   <li>扫描 {@code com.macro.mall.tiny} 包及子包，注册所有带 @Service/@Controller 等注解的 Bean</li>
 *   <li>根据 classpath 中的 starter 自动装配配置</li>
 *   <li>启动内嵌 Tomcat，应用开始对外提供 HTTP 服务</li>
 * </ol>
 */
@SpringBootApplication // 组合注解：自动配置 + 组件扫描（默认扫描本类所在包及子包）
@EnableScheduling // 开启定时任务调度，使项目内所有 @Scheduled 方法周期性执行
public class MallTinyApplication {

    // JVM 入口：整个应用从这里启动，控制权交给 Spring Boot 框架
    public static void main(String[] args) {
        SpringApplication.run(MallTinyApplication.class, args);
    }

}
