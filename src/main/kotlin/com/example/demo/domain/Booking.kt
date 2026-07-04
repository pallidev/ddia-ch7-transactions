package com.example.demo.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

/**
 * 회의실 예약.
 *
 * 책 Example 7-2(팬텀 / phantom) 예시.
 * 시스템 불변식: "같은 회의실, 겹치는 시간에는 2개 이상의 예약이 있을 수 없다."
 *
 * 예약 생성 흐름:
 *   1) SELECT 로 겹치는 기존 예약이 있는지 검사
 *   2) 없으면 INSERT
 *
 * 팬텀 문제: 두 트랜잭션이 동시에 1) 을 검사하면 둘 다 "없음" 을 본다.
 * 잠글(LOCK) 대상 행이 존재하지 않으므로 SELECT ... FOR UPDATE 도 소용이 없다 —
 * 이것이 phantom 이며, 해결은 Serializable 격리 수준 또는 충돌의 구체화(materializing conflicts)
 * 또는 (가장 실용적으로) 유일 제약(unique constraint) 이다.
 *
 * 여기서는 (roomId, startTime) 조합에 유일 제약을 걸어 "최후의 방어선" 으로 삼는다.
 * 검사-후-쓰기가 팬텀 때문에 뚫려도, DB 제약이 중복 INSERT 중 하나를 abort 시킨다.
 */
@Entity
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "uk_room_start", columnNames = ["roomId", "startTime"]),
    ],
)
class Booking(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    var roomId: Long,

    var startTime: LocalDateTime,

    var endTime: LocalDateTime,

    var userId: Long,
) {
    /** 두 예약이 시간이 겹치는지(같은 방 전제). */
    fun overlaps(other: Booking): Boolean =
        startTime < other.endTime && other.startTime < endTime
}
