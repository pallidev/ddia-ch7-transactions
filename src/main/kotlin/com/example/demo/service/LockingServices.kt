package com.example.demo.service

import com.example.demo.repository.AccountRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 갱신 손실(lost update) 방지를 위한 두 가지 잠금 전략(책 p.243~245).
 *
 * [PessimisticLockService] — 비관적 락: SELECT ... FOR UPDATE 로 미리 행을 잠근다.
 *   책의 "Explicit locking"(Example 7-1) 에 대응.
 * [OptimisticLockService] — 낙관적 락: @Version 컬럼으로 commit 시점에 충돌을 감지.
 *   책의 "Compare-and-set"(p.245) 에 대응. 충돌이 적을 때 성능이 좋다.
 */
@Service
class PessimisticLockService(private val accounts: AccountRepository) {

    /**
     * FOR UPDATE 로 잠근 뒤 +1. 두 번째 트랜잭션은 첫 번째가 커밋/롤백할 때까지 대기 → 직렬화.
     * 동시성은 떨어지지만 절대 갱신 손실이 나지 않는다.
     */
    @Transactional
    fun incrementWithLock(id: Long, by: Long = 1) {
        val acc = accounts.findWithLockingById(id) ?: error("계좌 없음: $id")
        acc.balance += by
        // 메서드 끝(커밋) 에서 잠금 해제.
    }
}

@Service
class OptimisticLockService(private val accounts: AccountRepository) {

    /**
     * 잠그지 않고 읽어서 +1. 커밋 시점에 @Version 이 바뀌었는지 검사.
     * 누군가 이미 수정했다면 [org.springframework.orm.ObjectOptimisticLockingFailureException] 발생 → 재시도 대상.
     * "일단 진행하고, 커밋할 때 검사한다" 가 낙관적 인 이유.
     */
    @Transactional
    fun incrementWithVersion(id: Long, by: Long = 1) {
        val acc = accounts.findByIdOrNull(id)!!
        acc.balance += by
    }
}
