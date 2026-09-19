# Baden HTTP P4 pilot 결과

실행일: 2026-09-17  
상태: `ENVIRONMENT_INVALID / RETRY_REQUIRED`

## 조건

- role: A baseline (`/private/tmp/otp-baden-build-LgbuLS/otp-baseline.jar`)
- graph SHA-256: `64ee49f2d24243ab21f88225d47f44aeab17017bb6a9b28a2d8d6e476b412bfc`
- corpus SHA-256: `42c6f861a704b1c6a246b2a55506267be1d4c4c49cb6f11ec8e2a962d0bef65c`
- JVM: Java 26.0.2.1, `-Xms512m -Xmx10g -XX:+UseG1GC`, NMT summary
- workload: official corpus-derived open-loop 1 rps, 120 seconds, 120 requests
- raw: `/private/tmp/otp-baden-http-native-20260917-p4/pilot-A/`

## 응답 결과

- 120/120 HTTP 200
- transport error 0
- routing error 0
- scheduling lag p50 7.81 ms, p95 10.94 ms, max 11.17 ms
- service latency p50 169.88 ms, p95 293.35 ms, max 1,095.70 ms
- natural Full GC 0, allocation/evacuation failure 0, OOM 0
- RSS sampled p50 8,335,744 KiB, max 8,453,664 KiB
- heap used `EU` max 4,800,512 KiB, old used `OU` stable near 3,036,355 KiB

## host pressure 결과

`host-before-vmstat.txt` 와 run 중 sampler를 비교했다.

| counter | 시작 | 종료 | delta |
| --- | ---: | ---: | ---: |
| `vm_stat Pageins` | 14,483,244 | 14,489,313 | +6,069 |
| `vm_stat Pageouts` | 232,997 | 233,504 | +507 |
| `vm_stat Swapins` | 182,029 | 182,060 | +31 |
| `vm_stat Swapouts` | 285,173 | 285,173 | 0 |
| `vm.swapusage used` | 973.31 MiB | 973.31 MiB | 0 |
| `memory_pressure` free | 76% | 77% (range 72–77%) | — |

`Swapins +31` 때문에 이 run은 플랜의 사전 no-pressure gate를 통과하지 못한다.
`Swapouts 0` 및 `vm.swapusage used` 변화 없음은 새로운 swap 할당/축적이 없었음을
보여주지만, 기존 swap 페이지를 읽은 효과는 배제하지 못한다. Pageouts +507도
함께 기록하며, 이 수치를 전부 OTP가 발생시켰다고 단정하지 않는다.

## 판정·다음 조치

P4 1 rps 요청 자체는 성공했지만, 호스트 swap-in 발생으로 성능 증거에 사용하지 않는다.
sampler에 `Swapins/Swapouts` 카운터를 추가했다. 새로운 부하를 시작하지 말고
호스트가 안정된 상태에서 동일 1 rps부터 재시도한다. 재시도에서도
`Swapins` delta가 0이 아니면 18 GiB Mac no-pressure 표본은 무효로 하고,
32 GiB 이상 host 또는 swap이 안정된 실행 환경으로 이동한다.
