package com.macro.mall.tiny.modules.monitor.dto;

import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 浏览器 SDK 和服务端共用的一条事件“外壳”，统一放置各种事件都会有的字段。
 *
 * <p>错误、性能、Replay 等事件各自不同的内容放在 {@code data} 里。它是一个可变的键值对象，
 * Java 编译器无法预先确认里面每个值的类型，所以接入服务会根据 {@code eventType} 再逐项检查。</p>
 *
 * <p>Lombok 的 {@code @Data} 会自动生成读取和修改字段的方法；Controller 上的 {@code @Valid}
 * 会检查字段旁边的必填等规则。通过这些通用检查后，还要继续做事件类型专属检查。</p>
 */
@Data
public class MonitorEventEnvelope {

    /** SDK 为每条事件生成的唯一编号；后台用它识别重复上报，避免重复累计。 */
    @NotBlank
    private String eventId;

    /** 项目标识；后端先据此查项目，再核对请求头里的事件上报 Key。 */
    @NotBlank
    private String projectId;

    /** 事件类型；后端据此选择对应的校验规则、Kafka 分类队列和数据库表。 */
    @NotNull
    private MonitorEventType eventType;

    /** 事件发生时间，单位是从 1970 年起算的毫秒；调用记录会改用自身的开始时间。 */
    @NotNull
    private Long timestamp;

    private String sessionId;
    private String userId;
    private String release;
    private String environment = "production";
    private String pageUrl;
    private String sdkVersion;
    /** 事件产生时确定的业务 Trace ID；不使用延迟上报请求自己的 Trace ID 代替。 */
    private String traceId;
    /** 浏览器和屏幕尺寸等设备信息；作为 JSON 对象保存，入库前会检查其中的嵌套内容并脱敏。 */
    private Map<String, Object> device = new HashMap<>();

    /** 当前事件类型自己的数据。默认空对象，避免请求没带此字段时 Java 中出现 null。 */
    @NotNull
    private Map<String, Object> data = new HashMap<>();
}
