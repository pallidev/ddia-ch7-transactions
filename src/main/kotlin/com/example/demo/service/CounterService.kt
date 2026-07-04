package com.example.demo.service

import com.example.demo.repository.AccountRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * 카운터/잔액 증가 — 책 Fig 7-1 의 핵심 예시: **갱신 손실(Lost Update)**.
 *
 * 두 클라이언트가 같은 행을 read→modify→write 하면 한 쪽의 수정이 묵살된다.
 * 0 에서 두 번 +1 해야 1 인데, 경쟁 때문에 1 이 되는 현상.
 *
 * - [incrementUnsafe] — 읽고(in-memory) +1 해서 저장. lost update 발생.
 *   (ORM 으로 엔티티를 읽어 +1 후 save 하면 기본적으로 이 꼴. 책 p.243)
 * - [incrementAtomic] — DB 원자 갱신(`balance = balance + ?`). 책의 "Atomic write operations".
 *
 * 명시적 잠금 / 낙관적 락은 별도 서비스([PessimisticLockService], [OptimisticLockService]).
 */
@Service
class CounterService(private val accounts: AccountRepository) {

    /** 안전하지 않은 read-modify-write. 동시에 실행되면 한 쪽 증가가 사라진다. */
    fun incrementUnsafe(id: Long, by: Long = 1) {
        val acc = accounts.findByIdOrNull(id)!!
        acc.balance += by
        accounts.save(acc)
    }

    /** DB 수준 원자 갱신(벌크 UPDATE). 행 잠금으로 직렬화되어 lost update 가 발생하지 않는다. */
    fun incrementAtomic(id: Long, by: Long = 1) = accounts.addBalance(id, by)
}
