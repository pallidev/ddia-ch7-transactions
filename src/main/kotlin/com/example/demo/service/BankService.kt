package com.example.demo.service

import com.example.demo.domain.Account
import com.example.demo.repository.AccountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * 은행 서비스 — ACID 의 A(원자성) 와 격리 수준을 보여준다.
 *
 * [transferUnsafe] / [transferSafe] 두 버전을 둬서,
 * "트랜잭션이 없으면 부분 실패(partial failure) 가 어떻게 데이터를 망가뜨리는가"
 * 와 "@Transactional 하나로 전부 롤백되는 원자성" 을 비교한다.
 */
@Service
class BankService(
    private val accounts: AccountRepository,
) {
    /**
     * A 계좌에서 B 계좌로 amount 원 이체.
     * 출금 후, 입금 직전에 [boom] 이 true 면 예외를 던져 "중간에 장애" 를 흉내낸다.
     *
     * 트랜잭션 없이(또는 호출부에서 트랜잭션을 안 묶으면) 이 메서드를 쓰면:
     *   출금은 커밋됐는데 입금 전에 장애 → 돈이 증발.
     * 이것이 책이 말하는 "partial failure". ACID 의 Atomicity 가 없는 세계다.
     */
    fun transferUnsafe(fromId: Long, toId: Long, amount: Long, boom: () -> Boolean = { false }) {
        val from = accounts.findById(fromId).orElseThrow()
        val to = accounts.findById(toId).orElseThrow()
        from.withdraw(amount)
        accounts.save(from) // ← 이미 영속화(커밋). 여기서 장애가 나면?

        if (boom()) error("출금 후 입금 전에 시스템 장애 발생!")

        to.deposit(amount)
        accounts.save(to)
    }

    /**
     * 같은 로직이지만 @Transactional 로 묶었다.
     * 예외가 나면 출금까지 포함해 **전부 롤백** 된다 → all-or-nothing.
     * 이것이 ACID 의 A(Atomicity = abortability).
     *
     * 격리 수준도 인자로 받아, READ_COMMITTED / REPEATABLE_READ / SERIALIZABLE
 * 를 바꿔가며 read skew 동작을 실험할 수 있게 한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun transfer(fromId: Long, toId: Long, amount: Long, boom: () -> Boolean = { false }) {
        transferUnsafe(fromId, toId, amount, boom)
    }

    /** 잔액 조회. 격리 수준에 따라 더러운 읽기/스냅샷 읽기 동작이 달라진다. */
    fun balance(id: Long): Long = accounts.findById(id).orElseThrow().balance

    fun account(id: Long): Account = accounts.findById(id).orElseThrow()

    /** 두 계좌 잔액 합. read skew 예시(Fig 7-6) 에서 "두 번에 걸쳐 읽을 때" 쓴다. */
    fun balanceOf(accountId: Long): Long = accounts.findById(accountId).orElseThrow().balance
}
