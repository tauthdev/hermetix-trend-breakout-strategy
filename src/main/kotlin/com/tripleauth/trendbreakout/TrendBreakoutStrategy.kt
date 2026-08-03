package com.tripleauth.trendbreakout

import com.tripleauth.nexttrading.client.dto.CandleInterval
import com.tripleauth.nexttrading.strategy.Signal
import com.tripleauth.nexttrading.strategy.StrategyContext
import com.tripleauth.nexttrading.strategy.StrategySpec
import com.tripleauth.nexttrading.strategy.TradingStrategy
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * WMA 추세선 돌파 전략 (롱 온리, 다중 종목).
 *
 * 원본: turtle-trading 의 BackTradingV11Service (Bybit 1시간봉 백테스트, 롱/숏 양방향).
 * 모의투자 이관에서 바뀐 점:
 * - 공매도 불가 → 상방 돌파만 진입한다
 * - 익절/손절은 코어의 소프트웨어 브라켓에 위임한다 (원본의 트레일링 익절선 갱신은
 *   고정 익절률로 단순화 — 서버가 TRAILING_STOP 을 지원하면 복원 예정)
 * - 분봉 대신 현재가 스냅샷으로 돌파를 감지한다
 * - 종목별로 독립 상태를 유지하며, 예산은 종목 수로 나눠 배분한다
 *
 * 진입: 완성된 1시간봉 lookback 개로 가중 추세선을 만들고,
 *      현재가가 돌파선(예측 고점 + 갭)을 넘고 고점 기울기가 양수면 시장가 매수.
 * 청산: 손절(하방 지지선) / 익절(진입가 x (1+profitRate)) / 만료(expireHours 경과).
 */
@Component
class TrendBreakoutStrategy(
    private val properties: TrendBreakoutProperties,
) : TradingStrategy {

    private val logger = KotlinLogging.logger { }

    override val spec = StrategySpec(
        name = "trend-breakout",
        symbols = properties.symbols,
        candleInterval = CandleInterval.HOUR_1,
        candleLimit = properties.lookback + 1, // lookback + 진행 중 캔들
        pollInterval = Duration.ofSeconds(properties.pollSeconds),
    )

    /** 종목별 진입에 사용한 시간봉 시각 — 같은 구간 재진입 방지 */
    internal val lastEntryCandle = ConcurrentHashMap<String, Instant>()

    /** 종목별 진입 시각 — 만료 청산 판정용 */
    internal val entryAt = ConcurrentHashMap<String, ZonedDateTime>()

    override fun decide(context: StrategyContext): List<Signal> =
        properties.symbols.flatMap { symbol -> decideForSymbol(symbol, context) }

    private fun decideForSymbol(symbol: String, context: StrategyContext): List<Signal> {
        if (context.hasPosition(symbol)) {
            return decideExit(symbol, context)
        }

        entryAt.remove(symbol)
        if (context.hasOpenOrder(symbol)) return emptyList()

        return decideEntry(symbol, context)
    }

    private fun decideEntry(symbol: String, context: StrategyContext): List<Signal> {
        val candles = context.candles(symbol)
        if (candles.size < properties.lookback + 1) return emptyList()

        // 마지막 캔들은 진행 중 — 추세선은 완성된 캔들로만 계산한다
        val completed = candles.dropLast(1)
        val inProgress = candles.last()

        // 같은 시간봉 구간에서는 한 번만 진입을 시도한다
        if (lastEntryCandle[symbol] == inProgress.timestamp) return emptyList()

        val trend = TrendLine.of(completed.takeLast(properties.lookback))

        // 상승 추세가 아니면 롱 진입하지 않는다
        if (trend.highInclination <= BigDecimal.ZERO) return emptyList()

        val price = context.quote(symbol)?.price ?: return emptyList()
        if (price < trend.breakoutLine) return emptyList()

        // 거래량 필터 (설정 시): 진행 중 캔들 거래량이 가중 평균의 volumeRatio 배 이상
        if (properties.volumeRatio > BigDecimal.ZERO) {
            val required = trend.avgVolume.multiply(properties.volumeRatio)
            if (BigDecimal(inProgress.volume) < required) {
                logger.info { "[$symbol] 돌파 감지했으나 거래량 부족 / vol=${inProgress.volume} < $required" }
                return emptyList()
            }
        }

        val budget = context.buyingPower
            .multiply(properties.budgetRatio)
            .divide(BigDecimal(properties.symbols.size), 8, RoundingMode.HALF_EVEN)
        val quantity = budget.divide(price, 0, RoundingMode.DOWN)

        if (quantity < BigDecimal.ONE) {
            logger.warn { "[$symbol] 예산 부족으로 진입 스킵 / budget=$budget price=$price" }
            return emptyList()
        }

        lastEntryCandle[symbol] = inProgress.timestamp
        entryAt[symbol] = context.now

        val takeProfit = price.multiply(BigDecimal.ONE + properties.profitRate).setScale(2, RoundingMode.HALF_EVEN)
        val stopLoss = trend.supportLine.setScale(2, RoundingMode.HALF_EVEN)

        logger.info {
            "[$symbol] 돌파 진입 / price=$price 돌파선=${trend.breakoutLine.setScale(2, RoundingMode.HALF_EVEN)} " +
                "기울기=${trend.highInclination} qty=$quantity 익절=$takeProfit 손절=$stopLoss"
        }

        return listOf(
            Signal.Buy(
                symbol = symbol,
                quantity = quantity,
                takeProfitPrice = takeProfit,
                stopLossPrice = stopLoss,
            ),
        )
    }

    private fun decideExit(symbol: String, context: StrategyContext): List<Signal> {
        val openedAt = entryAt.getOrPut(symbol) { context.now }

        if (Duration.between(openedAt, context.now) < Duration.ofHours(properties.expireHours)) {
            return emptyList()
        }

        val quantity = context.holding(symbol)?.quantity ?: return emptyList()

        logger.info { "[$symbol] 만료 청산 / qty=$quantity (진입 후 ${properties.expireHours}시간 경과)" }
        entryAt.remove(symbol)

        return listOf(Signal.Sell(symbol = symbol, quantity = quantity))
    }
}
