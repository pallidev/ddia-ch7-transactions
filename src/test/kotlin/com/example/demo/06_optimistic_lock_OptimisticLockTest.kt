package com.example.demo

import com.example.demo.domain.Account
import com.example.demo.repository.AccountRepository
import com.example.demo.service.OptimisticLockService
import com.example.demo.support.ConcurrentTx
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.TransactionDefinition

/**
 * **낙관적 락(Optimistic Lock, @Version)** — 책 p.245 "Compare-and-set" 와 대응.
 *
 * 읽을 때는 아무도 잠그지 않는다. 커밋할 때 @Version 컬럼으로 "내가 읽은 이후 누가 바꿨는가"
 * 를 검사한다. 충돌하면 [ObjectOptimisticLockingFailureException] → 재시도 대상.
 *
 * 갱신 손실 해결책 3가지 비교:
 *   - 원자 갱신(벌크 UPDATE) → LostUpdateTest
 *   - 비관적 락(FOR UPDATE) → PessimisticLockService
 *   - 낙관적 락(@Version)  → 이 테스트
 */
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class OptimisticLockTest(
    private val concurrent: ConcurrentTx,
    private val accounts: AccountRepository,
    private val optimistic: OptimisticLockService,
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
    @DisplayName("@Version: 동시 수정 중 한쪽은 커밋 단계에서 충돌 감지 → abort")
    fun optimisticLockAbortsLoser() {
        val result = concurrent.runConcurrent(
            isolation = TransactionDefinition.ISOLATION_READ_COMMITTED,
            t1 = {
                startAsT1()
                val seen = accounts.findById(accId).orElseThrow()     // version=0
                println("[T1] 읽음 balance=${seen.balance}, version=${seen.version}")
                t1FinishedStep1()
                awaitT2Step1()
                seen.balance += 1
                accounts.save(seen)                                    // 커밋 시 version 검사
            },
            t2 = {
                startAsT2()
                awaitT1Step1()
                val seen = accounts.findById(accId).orElseThrow()     // version=0 (같은 스냅샷)
                println("[T2] 읽음 balance=${seen.balance}, version=${seen.version}")
                t2FinishedStep1()
                seen.balance += 1
                accounts.save(seen)                                    // 커밋 시 version 검사 → 한쪽 충돌
            },
        )

        val acc = accounts.findById(accId).orElseThrow()
        println("[결과] 최종 balance=${acc.balance}, version=${acc.version}, abort=${result.anyAborted}")

        // ★ 한쪽만 승인 → balance=1. 진 사람은 ObjectOptimisticLockingFailureException.
        assertThat(acc.balance).isEqualTo(1)
        assertThat(result.thrown()).anyMatch { it is ObjectOptimisticLockingFailureException }
    }

    @Test
    @DisplayName("@Version 으로 갱신 손실(lost update) 도 방지된다")
    fun preventsLostUpdate() {
        val result = concurrent.runConcurrent(
            isolation = TransactionDefinition.ISOLATION_READ_COMMITTED,
            t1 = {
                startAsT1()
                val seen = accounts.findById(accId).orElseThrow()
                t1FinishedStep1()
                awaitT2Step1()
                seen.balance += 1
                accounts.save(seen)
            },
            t2 = {
                startAsT2()
                awaitT1Step1()
                val seen = accounts.findById(accId).orElseThrow()
                t2FinishedStep1()
                seen.balance += 1
                accounts.save(seen)
            },
        )

        // 승자 한 번의 증가만 살아남음. 진 쪽은 재시도하면 +1 더 할 수 있다(앱 책임).
        val finalBalance = accounts.findById(accId).orElseThrow().balance
        println("[결과] balance=$finalBalance (낙관적 락이 lost update 차단), abort=${result.anyAborted}")
        assertThat(finalBalance).isEqualTo(1)
        assertThat(result.anyAborted).isTrue()
    }
}
