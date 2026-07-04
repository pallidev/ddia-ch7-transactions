package com.example.demo.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Version

/**
 * 은행 계좌.
 *
 * 책 Fig 7-1(카운터 경쟁 → 갱신 손실), Fig 7-3(원자성), Fig 7-6(읽기 비대칭/read skew) 의
 * 예시를 재현하기 위한 엔티티.
 *
 * - balance 는 원 단위의 정수(Long)로 저장한다. 부동소수점은 표현 오차가 생기므로
 *   금융 데이터에는 절대 쓰지 않는다(정수 센트/원 단위가 정석).
 * - @Version 은 "낙관적 락(Optimistic Lock)" 용이다.
 *   JPA 가 commit 시점에 버전을 검사해서, 읽은 이후 누군가 수정했다면
 *   [org.springframework.orm.ObjectOptimisticLockingFailureException] 을 던진다.
 *   책의 "Compare-and-set"(p.245) 과 대응된다.
 */
@Entity
class Account(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    var owner: String,

    var balance: Long,

    @Version
    var version: Long? = null,
) {
    /** 도메인 의미를 명확히 하기 위한 convenience. */
    fun deposit(amount: Long) {
        require(amount >= 0) { "음수 입금 불가: $amount" }
        balance += amount
    }

    fun withdraw(amount: Long) {
        require(amount >= 0) { "음수 출금 불가: $amount" }
        require(balance >= amount) { "잔액 부족: 보유=$balance, 출금=$amount" }
        balance -= amount
    }
}
