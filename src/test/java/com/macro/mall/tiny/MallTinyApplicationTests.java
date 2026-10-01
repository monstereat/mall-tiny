package com.macro.mall.tiny;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false"
})
public class MallTinyApplicationTests {

    @Test
    public void contextLoads() {
    }

}
