package com.tripleauth.trendbreakout

import com.tripleauth.hermetix.client.dto.Candle
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 가중 이동 추세선.
 *
 * 원본: turtle-trading IndicatorsService.getWMA — 최근 캔들일수록 큰 가중치를 주어
 * 고점/저점의 기울기(변화량 가중평균)를 구하고, 마지막 값에 기울기를 더해 다음 값을 예측한다.
 */
data class TrendLine(
    /** 예측 고점 (마지막 고점 + 가중 기울기) */
    val highPrice: BigDecimal,
    /** 예측 저점 */
    val lowPrice: BigDecimal,
    /** 고점 기울기 — 양수면 상승 추세 */
    val highInclination: BigDecimal,
    /** 저점 기울기 */
    val lowInclination: BigDecimal,
    /** 가중 평균 거래량 */
    val avgVolume: BigDecimal,
) {

    /** 고점-저점 갭. 돌파선은 예측 고점에서 갭만큼 위 */
    val gap: BigDecimal get() = highPrice - lowPrice

    /** 상방 돌파선 */
    val breakoutLine: BigDecimal get() = highPrice + gap

    /** 하방 지지선 — 롱 진입 시 손절선으로 사용 */
    val supportLine: BigDecimal get() = lowPrice - gap

    companion object {

        fun of(candles: List<Candle>): TrendLine {
            require(candles.size >= 2) { "추세선 계산에는 캔들 2개 이상이 필요합니다" }

            var weight = BigDecimal.ZERO
            var volumeWeight = BigDecimal.ONE
            var highInclination = BigDecimal.ZERO
            var lowInclination = BigDecimal.ZERO
            var avgVolume = BigDecimal(candles.first().volume)

            for (i in 1 until candles.size) {
                val w = BigDecimal(i)
                weight += w
                volumeWeight += BigDecimal(i + 1)
                highInclination += (candles[i].high - candles[i - 1].high).multiply(w)
                lowInclination += (candles[i].low - candles[i - 1].low).multiply(w)
                avgVolume += BigDecimal(candles[i].volume).multiply(BigDecimal(i + 1))
            }

            highInclination = highInclination.divide(weight, 8, RoundingMode.HALF_EVEN)
            lowInclination = lowInclination.divide(weight, 8, RoundingMode.HALF_EVEN)

            return TrendLine(
                highPrice = candles.last().high + highInclination,
                lowPrice = candles.last().low + lowInclination,
                highInclination = highInclination,
                lowInclination = lowInclination,
                avgVolume = avgVolume.divide(volumeWeight, 8, RoundingMode.HALF_EVEN),
            )
        }
    }
}
