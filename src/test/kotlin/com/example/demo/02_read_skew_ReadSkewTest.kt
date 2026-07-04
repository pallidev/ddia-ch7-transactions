package com.example.demo

import com.example.demo.domain.Account
import com.example.demo.repository.AccountRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 격리 수준 비교 — 책 p.236~239 **Read Skew(nonrepeatable read) vs Snapshot Isolation**.
 *
 * 한 트랜잭션이 같은 행을 두 번 읽는 사이에, 다른 트랜잭션이 그 행을 바꾸고 커밋한다면?
 *   - READ COMMITTED  : 두 번째 읽기에서 **새 값** 이 보인다 → nonrepeatable read (값이 바뀜)
 *   - REPEATABLE READ : 내 트랜잭션 시작 시점의 **스냅샷** 만 본다 → 값이 일관됨
 *
 * 이것이 책이 말하는 "스냅샷이 주는 일관된 읽기(consistent snapshot)" 의 가치다.
 */
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ReadSkewTest(
    private val txManager: PlatformTransactionManager,
    private val accounts: AccountRepository,
) {
    private var accId = 0L

    @BeforeEach
    fun setUp() {
        accounts.deleteAll()
        accId = accounts.save(Account(owner = "x", balance = 100)).id!!
    }

    @AfterEach
    fun tearDown() {
        accounts.deleteAll()
    }

    /** [readerIsolation] 격리 수준에서 T2 가 같은 행을 두 번 읽었을 때 값이 같은지(스냅샷 일관성) 검증. */
    private fun readTwiceAcrossConcurrentWrite(readerIsolation: Int): Pair<Long, Long> {
        val t2ReadOnce = CountDownLatch(1)   // T2 가 첫 읽기를 마쳤음
        val t1Done = CountDownLatch(1)       // T1 이 갱신을 커밋했음
        val firstRead = AtomicLong()
        val secondRead = AtomicLong()

        // T1: 별도 트랜잭션에서 balance 를 100 → 200 으로 바꾸고 커밋.
        val writer = thread(start = true) {
            t2ReadOnce.await()                                  // T2 가 먼저 한 번 읽게 함
            tx(TransactionDefinition.ISOLATION_READ_COMMITTED) {
                val acc = accounts.findById(accId).orElseThrow()
                acc.balance = 200
                accounts.save(acc)
            }
            t1Done.countDown()
        }

        // T2: [readerIsolation] 으로 같은 행을 두 번 읽는다. 그 사이 T1 이 커밋.
        val reader = thread(start = true) {
            tx(readerIsolation) {
                // 1차 캐시를 우회해 DB 값을 직접 읽는다(findById 는 캐시를 돌려줘 nonrepeatable read 를 가림).
                firstRead.set(accounts.findBalanceRaw(accId))
                t2ReadOnce.countDown()
                t1Done.await()                                  // T1 의 갱신 커밋을 기다림
                secondRead.set(accounts.findBalanceRaw(accId))
            }
        }

        writer.join(); reader.join()
        return firstRead.get() to secondRead.get()
    }

    private fun tx(isolation: Int, block: () -> Unit) {
        TransactionTemplate(txManager).apply { isolationLevel = isolation }.execute { block(); null }
    }

    @Test
    @DisplayName("READ COMMITTED: 같은 행을 두 번 읽으면 값이 바뀐다 (nonrepeatable read)")
    fun readCommittedAllowsNonrepeatableRead() {
        val (first, second) = readTwiceAcrossConcurrentWrite(TransactionDefinition.ISOLATION_READ_COMMITTED)
        println("[READ COMMITTED] 첫 읽기=$first, 둘째 읽기=$second")

        // ★ 첫 읽기 100, 둘째 읽기 200 → 같은 트랜잭션에서 값이 바뀜(nonrepeatable read)
        assertThat(first).isEqualTo(100)
        assertThat(second).isEqualTo(200)
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    @DisplayName("REPEATABLE READ: 같은 행을 두 번 읽어도 스냅샷으로 일관됨 (반복 읽기 보장)")
    fun repeatableReadIsConsistent() {
        val (first, second) = readTwiceAcrossConcurrentWrite(TransactionDefinition.ISOLATION_REPEATABLE_READ)
        println("[REPEATABLE READ] 첫 읽기=$first, 둘째 읽기=$second")

        // ★ 스냅샷 일관성: 둘 다 100. T1 의 커밋이 보이지 않는다.
        assertThat(first).isEqualTo(100)
        assertThat(second).isEqualTo(100)
        assertThat(first).isEqualTo(second)
    }
}
