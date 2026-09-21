package com.tripleauth.trendbreakout

import com.tripleauth.hermetix.client.dto.AccountResponse
import com.tripleauth.hermetix.client.dto.Candle
import com.tripleauth.hermetix.client.dto.Holding
import com.tripleauth.hermetix.client.dto.Quote
import com.tripleauth.hermetix.strategy.Signal
import com.tripleauth.hermetix.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZonedDateTime

class TrendBreakoutStrategyTest {

    private val properties = TrendBreakoutProperties(
        symbols = listOf("AAPL"),
        lookback = 10,
        profitRate = BigDecimal("0.04"),
        volumeRatio = BigDecimal.ZERO,
        expireHours = 12,
        budgetRatio = BigDecimal("0.5"),
    )

    private lateinit var strategy: TrendBreakoutStrategy

    @BeforeEach
    fun setUp() {
        strategy = TrendBreakoutStrategy(properties)
    }

    /** 매 시간 고점/저점이 step 씩 움직이는 캔들 시퀀스 + 진행 중 캔들 */
    private fun candles(count: Int, startHigh: Double, step: Double): List<Candle> {
        val base = Instant.parse("2026-08-03T00:00:00Z")
        return (0..count).map { i ->
            val high = startHigh + step * i
            Candle(
                timestamp = base.plusSeconds(i * 3600L),
                open = BigDecimal(high - 2),
                high = BigDecimal(high),
                low = BigDecimal(high - 4),
                close = BigDecimal(high - 1),
                volume = 1000,
            )
        }
    }

    private fun context(
        candles: List<Candle>,
        price: String,
        holdingQty: String? = null,
        now: ZonedDateTime = ZonedDateTime.now(),
    ) = StrategyContext(
        now = now,
        quotes = mapOf(
            "AAPL" to Quote("AAPL", BigDecimal(price), null, null, 0, null, null, Instant.now()),
        ),
        candles = mapOf("AAPL" to candles),
        account = AccountResponse("acc_main", null, "USD", BigDecimal("10000"), BigDecimal("10000"), "ACTIVE"),
        holdings = holdingQty?.let { mapOf("AAPL" to Holding("AAPL", BigDecimal(it), BigDecimal("100"), null, null, null, null)) } ?: emptyMap(),
        openOrders = emptyList(),
        buyingPower = BigDecimal("10000"),
    )

    @Test
    fun `상승 추세에서 돌파선을 넘으면 지지선을 손절가로 매수한다`() {
        // 상승 추세: 고점 100→110 (기울기 +1), 예측고점 111, 예측저점 107, 갭 4 → 돌파선 115
        val data = candles(10, startHigh = 100.0, step = 1.0)

        val signals = strategy.decide(context(data, price = "116"))

        assertThat(signals).hasSize(1)
        val buy = signals[0] as Signal.Buy
        assertThat(buy.quantity).isEqualByComparingTo(BigDecimal("43")) // 10000*0.5/116
        assertThat(buy.stopLossPrice).isNotNull()
        assertThat(buy.takeProfitPrice).isEqualByComparingTo(BigDecimal("120.64")) // 116*1.04
    }

    @Test
    fun `돌파선 아래에서는 진입하지 않는다`() {
        // 예측고점 110 + 갭 4 = 돌파선 114 → 113 은 미돌파
        val data = candles(10, startHigh = 100.0, step = 1.0)

        assertThat(strategy.decide(context(data, price = "113"))).isEmpty()
    }

    @Test
    fun `하락 추세에서는 돌파해도 진입하지 않는다`() {
        val data = candles(10, startHigh = 110.0, step = -1.0)

        assertThat(strategy.decide(context(data, price = "999"))).isEmpty()
    }

    @Test
    fun `같은 시간봉 구간에서는 재진입하지 않는다`() {
        val data = candles(10, startHigh = 100.0, step = 1.0)

        assertThat(strategy.decide(context(data, price = "116"))).hasSize(1)
        assertThat(strategy.decide(context(data, price = "116"))).isEmpty()
    }

    @Test
    fun `보유 중이고 만료 시간이 지나면 전량 매도한다`() {
        val now = ZonedDateTime.now()
        strategy.entryAt["AAPL"] = now.minusHours(13)

        val signals = strategy.decide(context(candles(10, 100.0, 1.0), price = "116", holdingQty = "43", now = now))

        assertThat(signals).hasSize(1)
        assertThat((signals[0] as Signal.Sell).quantity).isEqualByComparingTo(BigDecimal("43"))
    }

    @Test
    fun `보유 중이지만 만료 전이면 대기한다`() {
        val now = ZonedDateTime.now()
        strategy.entryAt["AAPL"] = now.minusHours(1)

        assertThat(strategy.decide(context(candles(10, 100.0, 1.0), price = "116", holdingQty = "43", now = now))).isEmpty()
    }

    @Test
    fun `재시작으로 발견한 포지션에 복구 브라켓을 등록한다`() {
        // 평균단가 100 → 익절 104.00, 현재 추세선 지지선 102.00 (예측저점 106 - 갭 4)
        val signals = strategy.decide(context(candles(10, 100.0, 1.0), price = "103", holdingQty = "43"))

        assertThat(signals).isEmpty()
        val bracket = strategy.recoveryBrackets["AAPL"]
        assertThat(bracket?.takeProfitPrice).isEqualByComparingTo(BigDecimal("104.00"))
        assertThat(bracket?.stopLossPrice).isEqualByComparingTo(BigDecimal("102.00"))
    }

    @Test
    fun `복구 손절선을 하회하면 전량 청산한다`() {
        val signals = strategy.decide(context(candles(10, 100.0, 1.0), price = "101", holdingQty = "43"))

        assertThat(signals).hasSize(1)
        assertThat((signals[0] as Signal.Sell).quantity).isEqualByComparingTo(BigDecimal("43"))
        assertThat(strategy.recoveryBrackets["AAPL"]).isNull()
    }

    @Test
    fun `복구 익절가에 도달하면 전량 청산한다`() {
        val signals = strategy.decide(context(candles(10, 100.0, 1.0), price = "104.5", holdingQty = "43"))

        assertThat(signals).hasSize(1)
        assertThat((signals[0] as Signal.Sell).quantity).isEqualByComparingTo(BigDecimal("43"))
    }

    @Test
    fun `포지션이 사라지면 복구 브라켓도 정리된다`() {
        strategy.recoveryBrackets["AAPL"] = TrendBreakoutStrategy.RecoveryBracket(
            takeProfitPrice = BigDecimal("104.00"),
            stopLossPrice = BigDecimal("102.00"),
        )

        strategy.decide(context(candles(10, 100.0, 1.0), price = "113"))

        assertThat(strategy.recoveryBrackets["AAPL"]).isNull()
    }
}
