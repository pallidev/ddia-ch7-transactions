package com.example.demo

import com.example.demo.domain.Doctor
import com.example.demo.repository.DoctorRepository
import com.example.demo.service.OnCallService
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
 * 책 Fig 7-8 — **쓰기 비대칭(Write Skew)**.
 *
 * 시나리오: Alice, Bob 두 의사가 동시에 당직을 포기한다.
 *   T1(Alice): "당직 2명이니 한 명 빠져도 되지" → Alice off
 *   T2(Bob)  : "당직 2명이니 한 명 빠져도 되지" → Bob off
 * Snapshot/Repeatable Read 에선 두 검사 모두 2를 보고 → 둘 다 포기 → 당직 0명 (불변식 위반!)
 *
 * 핵심: 두 트랜잭션은 서로 다른 행(Alice, Bob) 을 고치므로 갱신 손실도 더러운 쓰기도 아니다.
 * 그래서 Repeatable Read 가 이걸 못 막는다. 오직 Serializable 또는 명시적 잠금(FOR UPDATE) 만이 막는다.
 */
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class WriteSkewTest(
    private val concurrent: ConcurrentTx,
    private val doctors: DoctorRepository,
    private val onCall: OnCallService,
) {
    private val shift = 1L
    private var aliceId = 0L
    private var bobId = 0L

    @BeforeEach
    fun setUp() {
        doctors.deleteAll()
        aliceId = doctors.save(Doctor(name = "Alice", shiftId = shift, onCall = true)).id!!
        bobId = doctors.save(Doctor(name = "Bob", shiftId = shift, onCall = true)).id!!
    }

    @AfterEach
    fun tearDown() {
        doctors.deleteAll()
    }

    @Test
    @DisplayName("REPEATABLE READ 에서 write skew 발생 → 당직 0명(불변식 위반)")
    fun writeSkewUnderRepeatableRead() {
        // 두 의사가 "동시에" 검사하고(둘 다 2명을 봄) 동시에 포기한다.
        concurrent.runConcurrent(
            isolation = TransactionDefinition.ISOLATION_REPEATABLE_READ,
            t1 = {
                startAsT1()
                val onCallCount = onCall.countOnCall(shift)        // 검사: 2명
                println("[T1/Alice] 당직 인원 = $onCallCount → 포기 결정")
                t1FinishedStep1()
                awaitT2Step1()
                // 쓰기: Alice 만 off
                val alice = doctors.findByIdOrNull(aliceId)!!
                alice.onCall = false
                doctors.save(alice)
            },
            t2 = {
                startAsT2()
                awaitT1Step1()
                val onCallCount = onCall.countOnCall(shift)        // 스냅샷: 여전히 2명
                println("[T2/Bob]   당직 인원 = $onCallCount → 포기 결정")
                t2FinishedStep1()
                // 쓰기: Bob 만 off
                val bob = doctors.findByIdOrNull(bobId)!!
                bob.onCall = false
                doctors.save(bob)
            },
        )

        val finalOnCall = onCall.countOnCall(shift)
        println("[결과] 최종 당직 인원 = $finalOnCall (불변식: 항상 1명 이상이어야 함)")

        // ★ write skew: 둘 다 포기해서 0명이 됐다. 불변식이 깨졌다!
        assertThat(finalOnCall).isZero()
    }

    @Test
    @DisplayName("해결책 A — SELECT ... FOR UPDATE 로 당직 행을 잠그면 직렬화되어 안전")
    fun preventWithPessimisticLock() {
        // goOffCallWithLock 은 당직 행을 FOR UPDATE 로 잠근 뒤 검사/포기한다.
        // T2 는 T1 이 잠금을 놓을 때(커밋) 까지 대기 → 그 후 검사하면 이미 1명이라 포기 거부.
        concurrent.runConcurrent(
            isolation = TransactionDefinition.ISOLATION_REPEATABLE_READ,
            t1 = {
                startAsT1()
                onCall.goOffCallWithLock(aliceId, shift)
                t1FinishedStep1()
            },
            t2 = {
                startAsT2()
                awaitT1Step1()
                // T1 커밋 후 잠금 획득 → 이제 당직은 1명(Alice 빠짐) → Bob 은 포기 거부
                val gaveUp = onCall.goOffCallWithLock(bobId, shift)
                println("[T2/Bob]   포기 시도 결과 = $gaveUp (false 면 거부된 것)")
            },
        )

        val finalOnCall = onCall.countOnCall(shift)
        println("[결과] 최종 당직 인원 = $finalOnCall")

        // ★ 잠금 덕분에 한 명은 포기하지 못해 최소 1명 유지 → 불변식 보존
        assertThat(finalOnCall).isEqualTo(1)
    }
}
