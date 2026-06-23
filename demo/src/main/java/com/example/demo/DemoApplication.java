package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 主启动类。
 * <p>
 * {@code @SpringBootApplication} 是一个组合注解，等价于同时使用：
 * <ul>
 *   <li>{@code @SpringBootConfiguration} — 声明当前类为配置类</li>
 *   <li>{@code @EnableAutoConfiguration} — 启用 Spring Boot 自动配置</li>
 *   <li>{@code @ComponentScan} — 自动扫描当前包及子包下的组件</li>
 * </ul>
 */
@SpringBootApplication
public class DemoApplication {

    /**
     * 应用程序入口方法。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

}
