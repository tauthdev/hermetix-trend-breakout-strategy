package com.tripleauth.trendbreakout

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

@ConfigurationProperties(prefix = "trend-breakout")
data class TrendBreakoutProperties(
    /** 감시 종목 목록. 종목마다 독립적으로 진입/청산한다 */
    val symbols: List<String> = listOf("AAPL"),
    /** 캔들 주기 (1m/5m/1h/1d). KRX 브로커(kis/kiwoom)는 1d 만 지원 */
    val candleInterval: String = "1h",
    /** 추세선(WMA) 계산에 사용할 캔들 개수 */
    val lookback: Int = 120,
    /** 익절 목표 수익률 (0.04 = +4%) */
    val profitRate: BigDecimal = BigDecimal("0.04"),
    /**
     * 거래량 필터 배수. 진행 중 캔들의 거래량이 (가중 평균 거래량 x 이 값) 이상일 때만 진입.
     * 0 이면 필터를 끈다.
     */
    val volumeRatio: BigDecimal = BigDecimal.ZERO,
    /** 진입 후 이 시간(시간 단위)이 지나면 강제 청산 */
    val expireHours: Long = 12,
    /** 주문 가능 현금 중 진입에 사용할 비율. 종목 수로 나눠 배분된다 */
    val budgetRatio: BigDecimal = BigDecimal("0.5"),
    /** 전략 호출 주기 (초) */
    val pollSeconds: Long = 60,
)
