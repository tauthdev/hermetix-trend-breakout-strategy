# next-trend-breakout-strategy

WMA **추세선 돌파 전략** (롱 온리) — [next-trading-core](https://github.com/tauthdev/next-trading-core) 기반 넥스트증권 모의투자 봇.

원본은 turtle-trading 프로젝트의 BackTradingV11 (Bybit 1시간봉 백테스트, 롱/숏 양방향)이며, 주식 모의투자 환경에 맞게 롱 방향만 이식했습니다.

## 전략 로직

- **추세선**: 완성된 1시간봉 `lookback`개의 고점/저점에 최근 캔들일수록 큰 가중치를 주어 기울기를 구하고, 다음 고점/저점을 예측
- **진입**: 현재가가 돌파선(예측 고점 + 고저 갭)을 넘고 고점 기울기가 양수(상승 추세)이면, 주문 가능 현금의 `budget-ratio` 만큼 시장가 매수. 거래량 필터(`volume-ratio`) 선택 가능
- **손절**: 하방 지지선(예측 저점 − 갭) — 코어 소프트웨어 브라켓이 자동 실행
- **익절**: 진입가 × (1 + `profit-rate`)
- **만료 청산**: 진입 후 `expire-hours` 경과 시 시장가 전량 매도

원본 대비 단순화: 트레일링 익절선 갱신은 고정 익절률로 대체 (서버가 TRAILING_STOP 지원 시 복원 예정).

## 실행

```bash
export NEXT_CLIENT_ID=pk_test_...
export NEXT_CLIENT_SECRET=sk_test_...
./gradlew bootRun
```

## 설정 (application.yml)

```yaml
trend-breakout:
  symbol: AAPL      # 감시 종목
  lookback: 120     # 추세선 계산 캔들 수
  profit-rate: 0.04 # 익절률
  volume-ratio: 0   # 거래량 필터 배수 (0 = 끔)
  expire-hours: 12  # 만료 청산 시간
  budget-ratio: 0.5 # 진입 예산 비율
  poll-seconds: 60  # 판정 주기
```
