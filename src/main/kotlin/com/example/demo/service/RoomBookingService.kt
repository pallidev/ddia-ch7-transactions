package com.example.demo.service

import com.example.demo.domain.Booking
import com.example.demo.repository.BookingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 회의실 예약 — 책 Example 7-2 **팬텀(phantom)** 예시.
 *
 * 불변식: "같은 방, 겹치는 시간대엔 예약이 2개 이상일 수 없다."
 * 흐름: (1) 겹치는 기존 예약 검색 (2) 없으면 INSERT.
 *
 * 팬텀 문제: 잠글(LOCK) 대상 행이 아직 존재하지 않으므로 SELECT ... FOR UPDATE
 * 로는 막을 수 없다. 두 트랜잭션이 동시에 "겹치는 예약 없음" 을 보고 INSERT → 중복 예약.
 */
@Service
class RoomBookingService(
    private val bookings: BookingRepository,
) {
    /** 겹치는 예약이 있는가. (격리 수준/동시성에 따라 보이는 결과가 다르다.) */
    fun hasConflict(roomId: Long, start: LocalDateTime, end: LocalDateTime): Boolean =
        bookings.findByRoomIdAndStartTimeLessThanAndEndTimeGreaterThan(roomId, end, start).isNotEmpty()

    /**
     * 검사 후 예약 생성(안전장치 없음). ConcurrentTx 로 감싸서 원하는 격리 수준으로 호출.
     * @return 예약을 생성했는가.
     */
    fun bookUnchecked(roomId: Long, start: LocalDateTime, end: LocalDateTime, userId: Long): Boolean {
        if (hasConflict(roomId, start, end)) return false
        bookings.save(Booking(roomId = roomId, startTime = start, endTime = end, userId = userId))
        return true
    }

    /**
     * 해결책: 겹침 판단을 "유일 제약(unique constraint)" 으로 구체화(materializing).
     * (room_id, start_time) 조합에 유일 제약을 걸면, 두 동시 INSERT 중 하나가 제약 위반으로 abort.
     * 이것이 책이 말하는 "유일 제약은 username 사례의 간단한 해결책"(p.250) 을 예약에 적용한 것이다.
     * 실무에서는 더 정교하게 (room_id, 시간슬롯) 으로 잡는다.
     */
    @Transactional
    fun bookWithUniqueSlot(roomId: Long, start: LocalDateTime, end: LocalDateTime, userId: Long): Boolean {
        if (hasConflict(roomId, start, end)) return false
        // 제약 위반(동시 INSERT) 이 발생하면 DataIntegrityViolationException 이 터지며
        // 호출부에서 이를 "겹침" 으로 해석한다.
        bookings.save(Booking(roomId = roomId, startTime = start, endTime = end, userId = userId))
        return true
    }
}
