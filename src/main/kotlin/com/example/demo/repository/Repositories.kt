package com.example.demo.repository

import com.example.demo.domain.Account
import com.example.demo.domain.Booking
import com.example.demo.domain.Doctor
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface AccountRepository : JpaRepository<Account, Long> {
    /**
     * 비관적 쓰기 잠금(Pessimistic Write) = SELECT ... FOR UPDATE.
     * 책 p.243 "Explicit locking", Example 7-1 의 FOR UPDATE 와 동일.
     * 이 쿼리로 읽어온 행은 다른 트랜잭션이 읽기/쓰기 모두 블록된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockingById(id: Long): Account?

    /**
     * DB 수준의 원자 갱신(벌크 UPDATE). read-modify-write 를 SQL 한 줄로 처리해서
     * 갱신 손실(lost update) 을 원천 차단한다.
     * 책 p.243: `UPDATE counters SET value = value + 1 WHERE key = 'foo'`.
     */
    @Modifying
    @Query("update Account a set a.balance = a.balance + :delta where a.id = :id")
    fun addBalance(@Param("id") id: Long, @Param("delta") delta: Long): Int

    /**
     * 잔액만 스칼라로 직접 조회. JPQL 스칼라 프로젝션은 영속성 컨텍스트(1차 캐시) 를
     * 거치지 않고 DB 에서 값을 가져오므로, 같은 트랜잭션에서 여러 번 읽을 때
     * 격리 수준에 따른 nonrepeatable read / snapshot 동작을 정확히 관찰할 수 있다.
     */
    @Query("select a.balance from Account a where a.id = :id")
    fun findBalanceRaw(@Param("id") id: Long): Long
}

interface DoctorRepository : JpaRepository<Doctor, Long> {
    /** 특정 교대근무의 현재 당직 의사 수. write skew 검사에 쓰인다. */
    fun countByShiftIdAndOnCallTrue(shiftId: Long): Int

    fun findByShiftIdAndOnCallTrue(shiftId: Long): List<Doctor>

    /**
     * 당직 의사 행들을 FOR UPDATE 로 잠근다. 책 p.248 의 "해결책" 코드와 동일.
     * write skew 를 막으려면 "검사 대상 행" 을 잠가서 두 번째 트랜잭션이 검사를
     * 직렬화되게 만들어야 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Doctor d where d.shiftId = :shiftId and d.onCall = true")
    fun findOnCallDoctorsForUpdate(@Param("shiftId") shiftId: Long): List<Doctor>
}

interface BookingRepository : JpaRepository<Booking, Long> {
    /** 특정 방의 특정 시간대에 겹치는 예약이 있는지 검사. */
    fun findByRoomIdAndStartTimeLessThanAndEndTimeGreaterThan(
        roomId: Long,
        endTime: LocalDateTime,
        startTime: LocalDateTime,
    ): List<Booking>
}
