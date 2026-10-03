package com.macro.mall.tiny;

import com.macro.mall.tiny.modules.monitor.service.MonitorSamlRegistrationRepository;
import com.macro.mall.tiny.modules.monitor.service.MinioBucketService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false"
})
public class MallTinyApplicationTests {

    @MockitoBean
    private MonitorSamlRegistrationRepository samlRegistrationRepository;

    @MockitoBean
    private MinioBucketService minioBucketService;

    @Test
    public void contextLoads() {
    }

}
