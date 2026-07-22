package com.zihan.zhiwei;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Requires PostgreSQL + pgvector + MySQL + Redis + RabbitMQ infrastructure running")
class ZhiweiApplicationTests {

    @Test
    void contextLoads() {
    }

}
