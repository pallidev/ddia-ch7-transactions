package com.example.demo.support

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 동시성(race condition) 학습용 헬퍼.
 *
 * 핵심 아이디어: 동시성 버그는 "운이 나빠야만" 터지기 때문에 일반적인 테스트로는
 * 재현이 거의 불가능하다. 그래서 [CountDownLatch] 로 두 스레드의 실행 순서를
 * **결정적(deterministic)** 으로 묶어, 매번 100% 같은 방식으로 버그가 발생하게 만든다.
 *
 * 두 워커(T1, T2) 는 번호가 매겨진 단계(step1, step2) 사이에 "상대의 특정 단계가
 * 끝날 때까지 대기" 하는 식으로 교차된다. 이것으로 책의 타이밍 다이어그램
 * (Fig 7-1, Fig 7-8 등) 을 코드로 재현한다.
 *
 * 격리 수준(isolation) 은 트랜잭션마다 직접 지정한다. 이것이 책 7장의 핵심 질문인
 * "각 격리 수준별로 어떤 race condition 이 막히고 안 막히는가" 를 실험하는 스위치다.
 */
@Component
class ConcurrentTx(
    private val txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 두 개의 워커를 별도 스레드에서 동시에 실행한다.
     *
     * @param isolation 트랜잭션 격리 수준([TransactionDefinition.ISOLATION_*]).
     * @param t1 첫 번째 동시 트랜잭션 (수신자 [TxWorker] 로 latch 들에 접근)
     * @param t2 두 번째 동시 트랜잭션
     * @return 두 워커가 던진 예외(있다면) 를 담은 결과
     */
    fun runConcurrent(
        isolation: Int = TransactionDefinition.ISOLATION_DEFAULT,
        t1: TxWorker.() -> Unit,
        t2: TxWorker.() -> Unit,
    ): ConcurrentResult {
        // "출발 준비 완료" 신호 — 각자 자기 신호를 내리고 상대 신호를 기다린 뒤 동시 출발.
        val readyA = CountDownLatch(1)
        val readyB = CountDownLatch(1)
        // 단계(step1/step2) 교차용 신호들.
        val t1Step1Done = CountDownLatch(1)
        val t2Step1Done = CountDownLatch(1)
        val t1Step2Done = CountDownLatch(1)

        val pool: ExecutorService = Executors.newFixedThreadPool(2)
        val error1 = AtomicReference<Throwable?>(null)
        val error2 = AtomicReference<Throwable?>(null)

        fun worker(body: TxWorker.() -> Unit, errRef: AtomicReference<Throwable?>) = Runnable {
            try {
                val template = TransactionTemplate(txManager).apply {
                    isolationLevel = isolation
                    // 동시성 데모에서는 무한 대기보다 명시적 타임아웃이 낫다(교착상태 감지).
                    timeout = 10
                }
                template.execute {
                    TxWorker(readyA, readyB, t1Step1Done, t2Step1Done, t1Step2Done).body()
                    null
                }
            } catch (e: Throwable) {
                errRef.set(e)
                // 이 워커가 죽었을 때 다른 워커가 latch 에서 영원히 기다리지 않도록 신호를 푼다.
                t1Step1Done.countDown()
                t2Step1Done.countDown()
                t1Step2Done.countDown()
            }
        }

        pool.submit(worker(t1, error1))
        pool.submit(worker(t2, error2))
        pool.shutdown()
        check(pool.awaitTermination(60, TimeUnit.SECONDS)) { "동시 트랜잭션이 60초 내에 끝나지 않음(교착상태 의심)" }

        return ConcurrentResult(error1 = error1.get(), error2 = error2.get())
    }

    /**
     * 개별 트랜잭션 안에서 실행되는 워커 컨텍스트.
     * latch 들로 "상대방의 특정 단계가 끝날 때까지 대기" 한다.
     */
    inner class TxWorker(
        private val readyA: CountDownLatch,
        private val readyB: CountDownLatch,
        private val t1Step1Done: CountDownLatch,
        private val t2Step1Done: CountDownLatch,
        private val t1Step2Done: CountDownLatch,
    ) {
        /** 내가 T1 임을 선언하고, T2 도 준비될 때까지 대기 후 출발. */
        fun startAsT1() {
            readyA.countDown()
            readyB.await()
        }

        /** 내가 T2 임을 선언하고, T1 도 준비될 때까지 대기 후 출발. */
        fun startAsT2() {
            readyB.countDown()
            readyA.await()
        }

        /** T1 전용: 내 step1 이 끝났음을 알림. */
        fun t1FinishedStep1() = t1Step1Done.countDown()

        /** T2 전용: 내 step1 이 끝났음을 알림. */
        fun t2FinishedStep1() = t2Step1Done.countDown()

        /** T1 전용: 내 step2 가 끝났음을 알림. */
        fun t1FinishedStep2() = t1Step2Done.countDown()

        /** T1 의 step1 이 끝날 때까지 대기. */
        fun awaitT1Step1() = t1Step1Done.await()

        /** T2 의 step1 이 끝날 때까지 대기. */
        fun awaitT2Step1() = t2Step1Done.await()

        /** T1 의 step2 가 끝날 때까지 대기. */
        fun awaitT1Step2() = t1Step2Done.await()
    }
}

/** 동시 실행 결과. 어느 쪽이 예외(abort/교착/제약위반) 를 냈는지 담는다. */
data class ConcurrentResult(
    val error1: Throwable?,
    val error2: Throwable?,
) {
    /** 둘 중 하나라도 예외가 있었는지. */
    val anyAborted: Boolean get() = error1 != null || error2 != null

    fun thrown(): List<Throwable> = listOfNotNull(error1, error2)
}
