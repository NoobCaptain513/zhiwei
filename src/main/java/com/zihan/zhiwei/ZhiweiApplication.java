package com.zihan.zhiwei;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@MapperScan("com.zihan.zhiwei.mapper")
@EnableScheduling
public class ZhiweiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZhiweiApplication.class, args);
    }

}
