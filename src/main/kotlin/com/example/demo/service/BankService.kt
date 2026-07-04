package com.example.demo.service

import com.example.demo.repository.AccountRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * 은행 서비스 — ACID 의 A(원자성) 와 격리 수준을 보여준다.
 *
 * [transferUnsafe] / [transfer] 두 버전을 둬서,
 * "트랜잭션이 없으면 부분 실패(partial failure) 가 어떻게 데이터를 망가뜨리는가"
 * 와 "@Transactional 하나로 전부 롤백되는 원자성" 을 비교한다.
 */
@Service
class BankService(private val accounts: AccountRepository) {

    /**
     * A → B 로 amount 이체. 출금 후 입금 직전에 [boom] 이 true 면 예외를 던져 "중간 장애" 를 흉내낸다.
     * 트랜잭션 없이 쓰면 출금은 커밋됐는데 입금 전 장애 → 돈 증발. ACID 의 A 가 없는 세계.
     */
    fun transferUnsafe(fromId: Long, toId: Long, amount: Long, boom: () -> Boolean = { false }) {
        val from = accounts.findByIdOrNull(fromId)!!
        val to = accounts.findByIdOrNull(toId)!!
        from.withdraw(amount)
        accounts.save(from)

        if (boom()) error("출금 후 입금 전에 시스템 장애 발생!")

        to.deposit(amount)
        accounts.save(to)
    }

    /**
     * 같은 로직이지만 @Transactional 로 묶었다. 예외가 나면 출금까지 포함해 전부 롤백(all-or-nothing).
     * 이것이 ACID 의 A(Atomicity = abortability).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun transfer(fromId: Long, toId: Long, amount: Long, boom: () -> Boolean = { false }) =
        transferUnsafe(fromId, toId, amount, boom)

    /** 잔액 조회. 격리 수준에 따라 더러운 읽기/스냅샷 읽기 동작이 달라진다. */
    fun balance(id: Long): Long = accounts.findByIdOrNull(id)!!.balance
}
