package net.chrisrichardson.ftgo.orderservice.consistency

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

/**
 * OrderConsistencyChecker / OrderConsistencyScanner 配置属性。
 *
 * application.properties 示例:
 *   ftgo.consistency.warn-threshold=PT30M
 *   ftgo.consistency.critical-threshold=PT2H
 *   ftgo.consistency.mismatch-minutes=60
 *   ftgo.consistency.scan-interval-ms=300000
 */
@ConfigurationProperties(prefix = "ftgo.consistency")
data class ConsistencyCheckerProperties(
    /** 中间状态告警阈值（默认 30 分钟）*/
    @DefaultValue("PT30M")
    val warnThreshold: Duration = Duration.ofMinutes(30),

    /** 中间状态严重告警阈值（默认 2 小时）*/
    @DefaultValue("PT2H")
    val criticalThreshold: Duration = Duration.ofHours(2),

    /** 跨服务状态不一致检测的最短等待分钟数 */
    @DefaultValue("60")
    val mismatchMinutes: Int = 60,
)
