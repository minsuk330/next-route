# H-only Xms Docker 파일럿 결과

실험일: 2026-09-16 (KST)

## 목적

Mac native에서 수행한 `Xms512m/Xmx8g` elastic-heap 실험을 Docker에서도 같은
graph·요청 코퍼스·JVM heap 설정으로 반복해, Docker/Linux 런타임에서 A(원본)와
H-only(개선본)의 메모리·지연·정합성을 비교한다.

이 실험은 무압박 조건이다. 컨테이너 제한은 `--memory=10g --memory-swap=10g`로
두 셀에 동일하게 적용했으며, swap 또는 OOM이 발생하면 중단·기록하도록 했다.

## 고정 조건

| 항목 | 값 |
|---|---|
| graph | Helsinki `graph.obj` (SHA-256 `178375c8ebed47178d9ec3c09c45e526bc483775aabab0043a62c7dc1b6515d0`) |
| 요청 코퍼스 | 공식 Helsinki GTFS GraphQL 코퍼스, 408 cases, seed `20260915` |
| 구현 | A-only JAR vs H-only JAR |
| JVM | `-Xms512m -Xmx8g -XX:+UseG1GC` |
| Docker | Linux ARM64 VM, Temurin Java `25.0.4`, image digest `sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753` |
| 부하 | warmup 300 s + 측정 180 s, 8 RPS, 32 workers (셀별 순차 실행) |
| 수집 | HTTP 지연, Java PID VmRSS, cgroup current/anon/file, GC log, host/cgroup swap |

원시 자료: `/private/tmp/otp-http-xms-docker-20260916/`

## 결과

### 정합성·안정성

- A/H 각각 1,440 requests, HTTP 200 1,440건.
- 408개 case fingerprint를 측정 순서대로 비교했을 때 mismatch 0건.
- 두 셀 모두 warmup·measure 정상 종료, container OOM false.
- host swapins/swapouts 증가 0, `vm.swapusage used=0`, cgroup swap current 0.

### 지연 (ms; 낮을수록 빠름)

| 지표 | A | H | H vs A |
|---|---:|---:|---:|
| 평균 | 117.32 | 148.64 | +26.70% |
| p50 | 96.38 | 108.04 | +12.10% |
| p95 | 222.30 | 375.39 | +68.87% |
| p99 | 545.94 | 942.77 | +72.69% |
| max | 757.84 | 2,628.26 | +246.81% |

이번 한 쌍의 순차·비대응 측정에서는 H가 빨라졌다고 볼 수 없고, tail 지연은 H가
더 컸다. 다만 n=1 pair이며 Java/OS 런타임이 native 실험과 다르므로 PR용 성능
결론이 아니라 추가 반복이 필요한 파일럿 결과다.

### 메모리

Java PID VmRSS 기준:

| 지표 | A | H | H vs A |
|---|---:|---:|---:|
| p50 | 2,527.07 MiB | 2,393.41 MiB | -5.29% |
| p95 | 2,527.57 MiB | 2,467.69 MiB | -2.37% |
| max | 2,566.76 MiB | 2,507.37 MiB | -2.31% |

cgroup current p95는 A 3,151.53 MiB, H 3,044.20 MiB로 H가 3.41% 낮았다.
H의 max committed heap도 A 2,112 MiB, H 2,052 MiB로 약 2.84% 낮았다.

이는 Docker 무압박 조건에서 H의 메모리 절감 방향이 보인다는 뜻이지만, 기존
VPS에서 관찰한 약 20% 절감과 같은 크기의 효과는 재현하지 못했다. cgroup file
page-cache 차이는 런타임·실행 순서의 영향을 받으므로 코드 효과로 합산하지 않는다.

## native 결과와의 비교

기존 Mac native `Xms512m/Xmx8g` 파일럿에서는 A/H Java RSS p95가 각각 약
2,984/2,978 MiB로 사실상 동일했다. Docker에서는 H가 RSS p95 약 2.37% 낮아져
차이가 조금 나타났지만, 다음 조건이 동시에 달라 직접적인 Docker 인과 증명은
아니다.

- native: macOS ARM64, Java 26
- Docker: Linux ARM64 VM, Temurin Java 25.0.4
- Docker JVM의 관측 max committed heap: 약 2.1 GiB (native는 약 4.2 GiB)

따라서 현재 결과가 지지하는 것은 “Docker/Linux/JDK 조합에서 H의 메모리 이점이
조금 관측될 수 있다”는 가설뿐이다. “Docker가 원인이다” 또는 “모든 환경에서
20% 절감된다”는 결론은 아직 입증되지 않았다.

## 결론 및 다음 단계

1. 정합성은 통과했다.
2. 무압박 Docker에서 H의 Java RSS는 A보다 약 2~5% 낮았다.
3. 이 한 번의 측정에서는 H의 지연 우위가 없었고 tail은 오히려 악화됐다.
4. swap/OOM 없이 실행되어 메모리 압박 효과는 섞이지 않았다.
5. PR 증거로 사용하려면 동일 JDK/OS에서 native·cgroup-only·Docker를 단계적으로
   맞추고, A/H 순서를 교차한 반복 측정 및 통계 검정을 수행해야 한다.

