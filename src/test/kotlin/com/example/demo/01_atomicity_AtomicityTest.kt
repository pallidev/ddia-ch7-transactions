package com.example.demo

import com.example.demo.domain.Account
import com.example.demo.repository.AccountRepository
import com.example.demo.service.BankService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestConstructor

/**
 * ACID 의 A — **원자성(Atomicity = abortability)**.
 *
 * 트랜잭션이 없으면 "부분 실패(partial failure)" 가 데이터를 망가뜨린다.
 * 출금은 됐는데 입금 전에 장애 → 돈이 증발.
 * @Transactional 하나가 있으면 장애 시 출금까지 전부 롤백(all-or-nothing).
 */
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AtomicityTest(
    private val accounts: AccountRepository,
    private val bank: BankService,
) {
    private var fromId = 0L
    private var toId = 0L

    @BeforeEach
    fun setUp() {
        accounts.deleteAll()
        fromId = accounts.save(Account(owner = "Alice", balance = 1_000)).id!!
        toId = accounts.save(Account(owner = "Bob", balance = 0)).id!!
    }

    @AfterEach
    fun tearDown() {
        accounts.deleteAll()
    }

    @Test
    @DisplayName("트랜잭션 없음: 출금 후 입금 전 장애 → 돈 증발 (원자성 없음)")
    fun partialFailureWithoutTransaction() {
        // transferUnsafe 는 @Transactional 이 없다.
        // accounts.save(from) 은 즉시 커밋(각 save 가 별도 트랜잭션) 되고, boom 으로 입금 전 장애.
        assertThatThrownBy {
            bank.transferUnsafe(fromId, toId, amount = 100) { true }
        }.isInstanceOf(IllegalStateException::class.java)

        val from = bank.balance(fromId)
        val to = bank.balance(toId)
        println("[결과] from=$from, to=$to, 총합=${from + to} (원래 총합 1000)")

        // ★ 출금(1000→900) 만 반영되고 입금은 안 됨 → 총합이 1000 이 아닌 900. 돈 100 증발.
        assertThat(from).isEqualTo(900)
        assertThat(to).isEqualTo(0)
        assertThat(from + to).isEqualTo(900)
    }

    @Test
    @DisplayName("@Transactional: 장애 시 전부 롤백 → 총합 보존 (원자성)")
    fun atomicWithTransaction() {
        // transfer 는 @Transactional. 출금+입금이 한 단위.
        // boom 으로 입금 전 장애 → 출금까지 롤백.
        assertThatThrownBy {
            bank.transfer(fromId, toId, amount = 100) { true }
        }.isInstanceOf(IllegalStateException::class.java)

        val from = bank.balance(fromId)
        val to = bank.balance(toId)
        println("[결과] from=$from, to=$to, 총합=${from + to} (롤백으로 원복)")

        // ★ 전부 롤백 → 잔액 원복, 총합 1000 보존. 이것이 ACID 의 A.
        assertThat(from).isEqualTo(1_000)
        assertThat(to).isEqualTo(0)
        assertThat(from + to).isEqualTo(1_000)
    }

    @Test
    @DisplayName("@Transactional: 장애 없으면 정상 이체 → 총합 보존")
    fun happyPath() {
        bank.transfer(fromId, toId, amount = 300)

        assertThat(bank.balance(fromId)).isEqualTo(700)
        assertThat(bank.balance(toId)).isEqualTo(300)
        assertThat(bank.balance(fromId) + bank.balance(toId)).isEqualTo(1_000)
    }
}
