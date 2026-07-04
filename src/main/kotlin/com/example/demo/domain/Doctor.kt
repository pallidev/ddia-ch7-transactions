package com.example.demo.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id

/**
 * 당직 의사.
 *
 * 책 Fig 7-8(쓰기 비대칭 / write skew) 의 핵심 예시.
 * 시스템 불변식(invariant): "특정 교대근무(shift) 에는 항상 1명 이상 당직 의사가 있어야 한다."
 *
 * Alice 와 Bob 이 동시에 당직을 포기하면, 둘 다 "2명 있으니 한 명 빠져도 된다" 를
 * 검사한 뒤 자기 기록만 off 한다. 결과적으로 아무도 당직이 없게 된다.
 * 각 트랜잭션은 서로 다른 행(Alice, Bob) 을 수정하므로 갱신 손실도 더러운 쓰기도 아니다.
 * 이것이 write skew 이며, 오직 Serializable 격리 수준(또는 명시적 잠금) 만이 막을 수 있다.
 */
@Entity
class Doctor(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    var name: String,

    /** 교대근무 식별자. 같은 shift_id 끼리가 한 그룹이다. */
    var shiftId: Long,

    var onCall: Boolean = true,
)
