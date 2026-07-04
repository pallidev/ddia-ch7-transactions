# study-transaction

> *Designing Data-Intensive Applications*(DDIA) **7장 Transactions** 를
> **Spring Boot 4 + Kotlin + JPA** 코드로 직접 실행하며 체험하는 학습 프로젝트.

트랜잭션의 핵심인 **ACID, 동시성 race condition, 격리 수준**을
책의 예시(Fig 7-1, 7-6, 7-8, Example 7-2)를 그대로 코드로 옮겨 놓고,
각 동시성 버그를 **결정적으로 재현**하는 테스트와 그 **해결책**을 짝지어 두었다.

```bash
./gradlew test                                  # 전체 동시성 시나리오를 한 번에 실행
./gradlew test --tests "*.WriteSkewTest"        # 특정 개념만 보기
```

별도 DB 설치 없이 테스트는 **H2(MySQL 호환 모드)** 로 동작한다. 운영 설정(`main`)은 MySQL 그대로.

---

## 핵심 개념 ↔ 코드 매핑

| 책 예시 | 도메인 | race condition | 재현 테스트 | 해결책 |
|---|---|---|---|---|
| Fig 7-1 카운터 | `Account` | **갱신 손실**(lost update) | `LostUpdateTest` | 원자 갱신 / 비관적·낙관적 락 |
| Fig 7-6 Alice 두 계좌 | `Account` | **read skew**(nonrepeatable read) | `ReadSkewTest` | Repeatable Read(snapshot) |
| Fig 7-3 이체 | `Account` | **원자성**(atomicity) | `AtomicityTest` | `@Transactional` |
| Fig 7-8 의사 당직 | `Doctor` | **쓰기 비대칭**(write skew) ⭐ | `WriteSkewTest` | `SELECT ... FOR UPDATE` / Serializable |
| Example 7-2 회의실 | `Booking` | **팬텀**(phantom) | `PhantomTest` | 유일 제약(unique constraint) |
| — | `Account` | (낙관적 락 체험) | `OptimisticLockTest` | `@Version` |

각 테스트는 **① 문제 재현 → ② 해결책** 쌍으로 구성되어 있고, 실행 시 `[T1]/[T2]/[결과]` 로그로 동시성 버그가 눈에 보인다.

---

## 프로젝트 구조

```
src/main/kotlin/com/example/demo/
├── domain/                  # Account(+@Version), Doctor, Booking(+유일제약)
├── repository/              # FOR UPDATE 잠금 쿼리, 원자 갱신, 스칼라 조회
├── service/                 # BankService, CounterService, OnCallService,
│                            # RoomBookingService, LockingServices
└── support/ConcurrentTx.kt  # ★ latch 로 동시성 버그를 결정적으로 재현하는 헬퍼

src/test/kotlin/com/example/demo/
├── 01_atomicity_AtomicityTest.kt             # ACID 의 A (원자성)
├── 02_read_skew_ReadSkewTest.kt              # READ COMMITTED vs REPEATABLE READ
├── 03_lost_update_LostUpdateTest.kt          # 갱신 손실 ⭐
├── 04_write_skew_WriteSkewTest.kt            # 쓰기 비대칭 ⭐⭐ 가장 중요
├── 05_phantom_PhantomTest.kt                 # 팬텀
└── 06_optimistic_lock_OptimisticLockTest.kt  # 낙관적 락

docs/CH07_트랜잭션_학습.md     # 상세 학습 문서 (이 README 의 확장판)
```

---

## 학습 순서

1. **[`docs/CH07_트랜잭션_학습.md`](docs/CH07_트랜잭션_학습.md)** 을 먼저 읽는다 (개념 ↔ 테스트 매핑 + 핵심 요약).
2. `WriteSkewTest`, `LostUpdateTest` 부터 실행해 본다 (가장 핵심).
3. 각 테스트의 주석과 대응하는 서비스 메서드(`OnCallService.goOffCallWithLock` 등)에서 해결책 패턴을 확인한다.
4. 격리 수준을 바꿔가며(`ISOLATION_SERIALIZABLE` 등) 실험한다.

---

## 기술 스택

- Spring Boot 4.1 · Kotlin 2.3 · Java 25
- Spring Data JPA (Hibernate 7.4)
- 테스트 DB: H2 2.x (MySQL 모드) / 운영 DB: MySQL
