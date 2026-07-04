package com.example.demo.service

import com.example.demo.domain.Doctor
import com.example.demo.repository.DoctorRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 당직 의사 관리 — 책 Fig 7-8 **쓰기 비대칭(write skew)** 예시.
 *
 * 시스템 불변식: "한 shift 에는 항상 1명 이상 당직 의사가 있어야 한다."
 *
 * 검사-후-쓰기 흐름이 핵심:
 *   1) 현재 당직 인원이 2명 이상인가?
 *   2) 그렇다면 안전하다고 판단하고 본인 onCall = false.
 *
 * Snapshot/Repeatable Read 격리 수준에서는 두 트랜잭션이 모두 "2명 있음" 을
 * 보고 동시에 포기 → 불변식 위반. 각 트랜잭션은 서로 다른 행을 고치므로
 * 갱신 손실도 더러운 쓰기도 아니다. 이것이 write skew.
 */
@Service
class OnCallService(private val doctors: DoctorRepository) {

    /** 현재 shift 의 당직 인원 수. (격리 수준에 따라 "보이는" 값이 다르다.) */
    fun countOnCall(shiftId: Long): Int = doctors.countByShiftIdAndOnCallTrue(shiftId)

    /**
     * 검사 후 당직 포기(안전장치 없음). ConcurrentTx 로 원하는 격리 수준으로 감싸서 호출.
     * @return 포기에 성공했는가.
     */
    fun goOffCallUnchecked(doctorId: Long, shiftId: Long): Boolean {
        if (countOnCall(shiftId) < 2) return false // 동시성 때문에 이 검사가 "거짓말" 이 될 수 있다.
        val me = doctors.findByIdOrNull(doctorId)!!
        me.onCall = false
        doctors.save(me)
        return true
    }

    /**
     * 해결책 A: 당직 행을 SELECT ... FOR UPDATE 로 잠근 뒤 검사/포기.
     * 책 p.248 코드와 동일. 두 번째 트랜잭션은 잠금 해제까지 대기 → 직렬화 → 안전.
     */
    @Transactional
    fun goOffCallWithLock(doctorId: Long, shiftId: Long): Boolean {
        val onCall: List<Doctor> = doctors.findOnCallDoctorsForUpdate(shiftId)
        if (onCall.size < 2) return false
        val me = onCall.first { it.id == doctorId }
        me.onCall = false
        doctors.save(me)
        return true
    }
}
