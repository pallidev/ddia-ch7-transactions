package com.example.demo

import com.example.demo.domain.Account
import com.example.demo.repository.AccountRepository
import com.example.demo.service.CounterService
import com.example.demo.support.ConcurrentTx
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.TransactionDefinition

/**
 * 책 Fig 7-1 — **갱신 손실(Lost Update)**.
 *
 * 두 클라이언트가 같은 행을 read → modify → write 한다.
 * 0 에서 두 번 +1 해야 2 인데, 둘 다 "0" 을 읽고 각자 1 을 쓰면 → 최종 1.
 * 한 쪽 증가가 "묵살(clobber)" 된다.
 *
 * 비교:
 *   - 안전하지 않은 read-modify-write (ORM 패턴) → lost update 발생
 *   - DB 원자 갱신(`balance = balance + ?`) → 안전
 */
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class LostUpdateTest(
    private val concurrent: ConcurrentTx,
    private val accounts: AccountRepository,
    private val counter: CounterService,
) {
    private var accId = 0L

    @BeforeEach
    fun setUp() {
        accounts.deleteAll()
        accId = accounts.save(Account(owner = "counter", balance = 0)).id!!
    }

    @AfterEach
    fun tearDown() {
        accounts.deleteAll()
    }

    @Test
    @DisplayName("안전하지 않은 read-modify-write: 동시 +1/+1 → 2가 아니라 1 (갱신 손실)")
    fun lostUpdate() {
        concurrent.runConcurrent(
            isolation = TransactionDefinition.ISOLATION_REPEATABLE_READ,
            t1 = {
                startAsT1()
                val seen = accounts.findByIdOrNull(accId)!!.balance     // read = 0
                println("[T1] 읽은 값 = $seen")
                t1FinishedStep1()
                awaitT2Step1()                                                 // T2 도 읽을 때까지 대기
                writeIncrement(seen)                                           // write = seen + 1
            },
            t2 = {
                startAsT2()
                awaitT1Step1()
                val seen = accounts.findByIdOrNull(accId)!!.balance     // read = 0 (T1 미커밋)
                println("[T2] 읽은 값 = $seen")
                t2FinishedStep1()
                writeIncrement(seen)                                           // write = seen + 1
            },
        )

        val finalBalance = accounts.findBalanceRaw(accId)
        println("[결과] 최종 잔액 = $finalBalance (기대: 2, 실제: 1 → 한 증가가 손실)")

        // ★ lost update: 2가 되어야 하지만 1
        assertThat(finalBalance).isEqualTo(1)
    }

    @Test
    @DisplayName("해결책 — DB 원자 갱신(벌크 UPDATE) 으로 lost update 원천 차단")
    fun preventWithAtomicUpdate() {
        // 벌크 UPDATE(`balance = balance + ?`)는 DB 가 행 잠금으로 자동 직렬화한다.
        // 따라서 두 워커를 동시에 출발시키기만 하면 된다 — 한쪽이 먼저 잠금을 잡고 0→1 커밋,
        // 다른 쪽은 잠금 해제를 기다렸다가 1→2 커밋. latch 로 순서를 억지로 잡으면
        // 오히려 트랜잭션이 잠금을 든 채 대기하면서 교착(deadlock) 이 생긴다.
        concurrent.runConcurrent(
            isolation = TransactionDefinition.ISOLATION_READ_COMMITTED,
            t1 = {
                startAsT1()
                counter.incrementAtomic(accId)   // UPDATE balance = balance + 1 (원자, 행 잠금)
            },
            t2 = {
                startAsT2()
                counter.incrementAtomic(accId)   // 직렬화되어 순차 적용
            },
        )

        val finalBalance = accounts.findBalanceRaw(accId)
        println("[결과] 최종 잔액 = $finalBalance (기대: 2)")

        // ★ 원자 갱신은 두 +1 이 모두 반영 → 2
        assertThat(finalBalance).isEqualTo(2)
    }

    /** ORM 의 안전하지 않은 read-modify-write 를 흉내: 읽은 값에서 +1 해서 저장. */
    private fun writeIncrement(seen: Long) {
        val acc = accounts.findByIdOrNull(accId)!!
        acc.balance = seen + 1
        accounts.save(acc)
    }
}
