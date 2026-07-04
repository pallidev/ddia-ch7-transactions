package com.example.demo

import com.example.demo.domain.Booking
import com.example.demo.repository.BookingRepository
import com.example.demo.service.RoomBookingService
import com.example.demo.support.ConcurrentTx
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.TransactionDefinition
import java.time.LocalDateTime

/**
 * 책 Example 7-2 — **팬텀(Phantom)** 으로 인한 동시 예약.
 *
 * 흐름: (1) 겹치는 기존 예약 검색 (2) 없으면 INSERT.
 * 잠글 대상 행이 아직 없으므로 SELECT ... FOR UPDATE 로는 막을 수 없다.
 * 두 트랜잭션이 동시에 "겹치는 예약 없음" 을 보고 INSERT → 중복.
 *
 * 해결: 유일 제약(unique constraint on roomId+startTime) 이 최후의 방어선이 되어
 * 동시 INSERT 중 하나를 abort 시킨다(충돌의 구체화, materializing conflicts 와 같은 맥락).
 */
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PhantomTest(
    private val concurrent: ConcurrentTx,
    private val bookings: BookingRepository,
    private val rooms: RoomBookingService,
) {
    private val roomId = 1L
    private val start = LocalDateTime.of(2025, 1, 1, 12, 0)
    private val end = LocalDateTime.of(2025, 1, 1, 13, 0)

    @BeforeEach
    fun setUp() {
        bookings.deleteAll()
    }

    @AfterEach
    fun tearDown() {
        bookings.deleteAll()
    }

    @Test
    @DisplayName("팬텀: 두 클라이언트 동시 예약 → 둘 다 '겹침 없음' 을 보고 INSERT 시도")
    fun phantomDoubleBooking() {
        val result = concurrent.runConcurrent(
            isolation = TransactionDefinition.ISOLATION_REPEATABLE_READ,
            t1 = {
                startAsT1()
                val conflict = rooms.hasConflict(roomId, start, end)     // false (아무 예약 없음)
                println("[T1] 겹치는 예약 있나? = $conflict")
                t1FinishedStep1()
                awaitT2Step1()
                rooms.bookUnchecked(roomId, start, end, userId = 1)       // INSERT 시도
            },
            t2 = {
                startAsT2()
                awaitT1Step1()
                val conflict = rooms.hasConflict(roomId, start, end)     // false (T1 미커밋 → 여전히 없음)
                println("[T2] 겹치는 예약 있나? = $conflict")
                t2FinishedStep1()
                rooms.bookUnchecked(roomId, start, end, userId = 2)       // INSERT 시도
            },
        )

        val all: List<Booking> = bookings.findAll()
        println("[결과] 예약 수 = ${all.size}, abort 발생? = ${result.anyAborted}")

        // ★ 유일 제약(roomId+startTime) 이 중복 INSERT 하나를 막아준다.
        //   - 검사(1단계) 는 팬텀 때문에 둘 다 "없음" 을 봤지만,
        //   - DB 제약이 최종적으로 예약을 1개로 강제한다.
        assertThat(all).hasSize(1)
    }
}
