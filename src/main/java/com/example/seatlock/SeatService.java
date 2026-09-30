package com.example.seatlock;

import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class SeatService {

    private static final int SEAT_COUNT = 8;
    private static final long PROCESSING_MS = 1500;
    private static final long LOCK_WAIT_MS = 8000; // 비관적 락: 이 시간까지는 줄 서서 기다린다
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final long INTERACTIVE_LOCK_TIMEOUT_MS = 60_000; // 사람이 입력하다 탭을 닫고 가버린 경우의 안전장치

    private final Map<Integer, Seat> seats = new ConcurrentHashMap<>();
    // fair=true: 먼저 줄 선 순서대로 락을 준다 (선착순 대기열에 가깝게) — k6/시뮬레이션 버튼(reserveFixed)이 쓴다.
    private final Map<Integer, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private final List<Attempt> attempts = new CopyOnWriteArrayList<>();

    // 사람이 직접 이름을 입력하는 동안 좌석을 붙잡아두는 용도의 락 — HTTP 요청 하나(락 획득)와
    // 다른 요청(입력 완료 후 확정)이 서로 다른 스레드에서 일어나므로, 스레드 소유권이 있는
    // ReentrantLock 대신 "누가 들고 있는지"만 기록하는 단순한 맵으로 관리한다.
    // 소유권은 표시 이름이 아니라 발급한 토큰으로 확인한다 — 사용자가 입력창에서 이름을
    // 고쳐 써도(=최종 예약자 이름이 잠글 때와 달라져도) 같은 사람이면 확정이 되어야 하기 때문.
    private final Map<Integer, String> heldToken = new ConcurrentHashMap<>();
    private final Map<Integer, String> heldByDisplay = new ConcurrentHashMap<>();
    private final Map<Integer, Long> heldSince = new ConcurrentHashMap<>();

    public SeatService() {
        for (int i = 1; i <= SEAT_COUNT; i++) {
            seats.put(i, new Seat(i));
            locks.put(i, new ReentrantLock(true));
        }
    }

    public List<Seat> getSeats() {
        return IntStream.rangeClosed(1, SEAT_COUNT)
                .mapToObj(seats::get)
                .collect(Collectors.toList());
    }

    public List<String> getLog() {
        return log;
    }

    public List<Attempt> getAttempts() {
        return attempts;
    }

    public synchronized void reset() {
        seats.values().forEach(Seat::reset);
        log.clear();
        attempts.clear();
        heldToken.clear();
        heldByDisplay.clear();
        heldSince.clear();
        log("--- 초기화 ---");
    }

    private void log(String message) {
        log.add("[" + LocalTime.now().format(TIME_FMT) + "] " + message);
    }

    private Attempt addAttempt(int seatId, String user, String state) {
        Attempt a = new Attempt(seatId, user, state);
        attempts.add(a);
        return a;
    }

    private void removeAttempt(Attempt a) {
        attempts.remove(a);
    }

    /**
     * 버그 버전: "확인"과 "점유 표시" 사이에 아무 잠금도 없이 지연이 끼어든다.
     * 두 스레드가 동시에 들어오면 둘 다 AVAILABLE을 보고, 둘 다 성공해버릴 수 있다.
     */
    public ReserveResult reserveBuggy(int seatId, String user) {
        Seat seat = seats.get(seatId);
        if (seat == null) {
            return ReserveResult.fail("존재하지 않는 좌석입니다", "UNKNOWN");
        }

        log(user + " → 좌석 " + seatId + " 확인: " + seat.getStatus());
        if (seat.getStatus() != Seat.Status.AVAILABLE) {
            log(user + " → 좌석 " + seatId + " 실패 (이미 " + seat.getStatus() + ")");
            return ReserveResult.fail("이미 예약된 좌석입니다", seat.getStatus().name());
        }

        Attempt a = addAttempt(seatId, user, "PROCESSING");
        try {
            sleep(PROCESSING_MS); // 결제 처리 등을 흉내낸 지연. 이 사이에 잠금이 없다.

            seat.setStatus(Seat.Status.OCCUPIED);
            seat.setOccupiedBy(user);
            log(user + " → 좌석 " + seatId + " 예약 성공 (버그 버전, 잠금 없음)");
            return ReserveResult.success(seat.getStatus().name());
        } finally {
            removeAttempt(a);
        }
    }

    /**
     * 낙관적 락: 읽을 때는 막지 않고, 저장할 때 버전이 그대로인지 확인한다.
     * 여러 명이 동시에 같은 버전을 읽고 처리에 들어갈 수 있지만,
     * 먼저 커밋한 사람만 버전이 맞아서 성공하고 나머지는 저장 시점에 충돌로 실패한다.
     * → 비관적 락과 달리 "실패도 끝까지 처리한 뒤에" 늦게 드러난다.
     */
    public ReserveResult reserveOptimistic(int seatId, String user) {
        Seat seat = seats.get(seatId);
        if (seat == null) {
            return ReserveResult.fail("존재하지 않는 좌석입니다", "UNKNOWN");
        }

        Seat.Status seenStatus = seat.getStatus();
        int seenVersion = seat.getVersion();
        log(user + " → 좌석 " + seatId + " 확인(버전 " + seenVersion + "): " + seenStatus);

        if (seenStatus != Seat.Status.AVAILABLE) {
            log(user + " → 좌석 " + seatId + " 실패 (이미 " + seenStatus + ")");
            return ReserveResult.fail("이미 예약된 좌석입니다", seenStatus.name());
        }

        // 낙관적 락의 핵심: 남이 지금 뭘 하고 있는지 모른 채 각자 "PROCESSING"으로 동시에 진행한다.
        // 그래서 같은 좌석에 PROCESSING 표시가 여러 개 동시에 뜰 수 있다 — 이게 비관적 락과의 시각적 차이.
        Attempt a = addAttempt(seatId, user, "PROCESSING");
        try {
            sleep(PROCESSING_MS); // 이 사이에 다른 사람도 같은 버전을 읽고 진행할 수 있다.

            synchronized (seat) {
                if (seat.getVersion() != seenVersion) {
                    log(user + " → 좌석 " + seatId + " 충돌! 저장 시점에 버전이 이미 바뀜 (읽은 버전 "
                            + seenVersion + " → 현재 " + seat.getVersion() + ")");
                    return ReserveResult.fail("충돌: 저장하려는 사이 다른 사람이 먼저 예약했습니다 (버전 불일치)", seat.getStatus().name());
                }
                seat.setStatus(Seat.Status.OCCUPIED);
                seat.setOccupiedBy(user);
                seat.incrementVersion();
            }
            log(user + " → 좌석 " + seatId + " 예약 성공 (낙관적 락, 버전 " + seat.getVersion() + ")");
            return ReserveResult.success(seat.getStatus().name());
        } finally {
            removeAttempt(a);
        }
    }

    /**
     * 고친 버전: 비관적 락. 늦게 온 요청은 즉시 거절되는 게 아니라, 먼저 온 사람이 끝날 때까지
     * 실제로 줄 서서 기다린다 (lock.tryLock(timeout)). 그래서 "대기 중"이라는 진짜 비용이 보인다.
     * 대기열 맨 끝 사람은 그만큼 오래 기다리다가, 락을 얻고 나서야 이미 늦었다는 걸 안다.
     */
    public ReserveResult reserveFixed(int seatId, String user) {
        Seat seat = seats.get(seatId);
        ReentrantLock lock = locks.get(seatId);
        if (seat == null || lock == null) {
            return ReserveResult.fail("존재하지 않는 좌석입니다", "UNKNOWN");
        }

        // 락을 실제로 쥐기 전까지는 무조건 WAITING으로 표시한다.
        // (isLocked()로 미리 짐작하면, 두 요청이 마이크로초 차이로 거의 동시에 들어왔을 때
        //  아직 아무도 tryLock을 호출하기 전이라 둘 다 "안 잠겨있음"으로 보여 오판할 수 있다.)
        Attempt a = addAttempt(seatId, user, "WAITING");
        if (lock.isLocked()) {
            log(user + " → 좌석 " + seatId + " 대기열에 합류 (앞사람 처리 중)");
        }

        boolean acquired = false;
        try {
            acquired = lock.tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
            if (!acquired) {
                log(user + " → 좌석 " + seatId + " 대기 시간 초과로 포기");
                return ReserveResult.fail("대기 시간이 너무 길어 요청을 포기했습니다", seat.getStatus().name());
            }

            a.setState("PROCESSING");
            log(user + " → 좌석 " + seatId + " 잠금 획득, 확인: " + seat.getStatus());
            if (seat.getStatus() != Seat.Status.AVAILABLE) {
                log(user + " → 좌석 " + seatId + " 실패 (이미 " + seat.getStatus() + ")");
                return ReserveResult.fail("이미 예약된 좌석입니다", seat.getStatus().name());
            }

            seat.setStatus(Seat.Status.PROCESSING);
            sleep(PROCESSING_MS); // 잠금을 쥔 채로 처리하므로 남이 끼어들 수 없다.

            seat.setStatus(Seat.Status.OCCUPIED);
            seat.setOccupiedBy(user);
            seat.incrementVersion();
            log(user + " → 좌석 " + seatId + " 예약 성공 (비관적 락, 잠금 해제)");
            return ReserveResult.success(seat.getStatus().name());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ReserveResult.fail("처리 중 인터럽트가 발생했습니다", seat.getStatus().name());
        } finally {
            removeAttempt(a);
            if (acquired) {
                lock.unlock();
            }
        }
    }

    /**
     * 비관적 락 · 실제 사람 입력 버전.
     * "확인" 대신 진짜로 좌석을 붙잡아두고, 사람이 이름을 입력하는 동안(길이는 그 사람 마음대로)
     * 그 좌석은 다른 누구도 건드릴 수 없다. 이게 비관적 락의 "먼저 잠그고 시작한다"를
     * 알고리즘 지연이 아니라 실제 체감 시간으로 보여주는 버전이다.
     */
    public LockResult lockForInput(int seatId, String user) {
        Seat seat = seats.get(seatId);
        if (seat == null) {
            return LockResult.fail("존재하지 않는 좌석입니다", "UNKNOWN");
        }

        // 안전장치: 탭을 닫고 가버려서 오래 방치된 락은 강제로 풀어준다.
        Long since = heldSince.get(seatId);
        if (since != null && System.currentTimeMillis() - since > INTERACTIVE_LOCK_TIMEOUT_MS) {
            log("(자동 해제) 좌석 " + seatId + " 방치된 잠금을 초기화합니다");
            releaseHeld(seatId);
        }

        if (seat.getStatus() != Seat.Status.AVAILABLE) {
            log(user + " → 좌석 " + seatId + " 시도 → 이미 예약됨");
            return LockResult.fail("이미 예약된 좌석입니다", seat.getStatus().name());
        }

        String token = java.util.UUID.randomUUID().toString();
        String prevToken = heldToken.putIfAbsent(seatId, token);
        if (prevToken != null) {
            String prevHolder = heldByDisplay.getOrDefault(seatId, "다른 사람");
            log(user + " → 좌석 " + seatId + " 시도 → " + prevHolder + "님이 지금 입력 중이라 접근 거절");
            return LockResult.fail(prevHolder + "님이 지금 이 좌석을 예약하고 있습니다. 잠시 후 다시 시도해주세요.", seat.getStatus().name());
        }

        heldByDisplay.put(seatId, user);
        heldSince.put(seatId, System.currentTimeMillis());
        seat.setStatus(Seat.Status.PROCESSING);
        addAttempt(seatId, user, "PROCESSING");
        log(user + " → 좌석 " + seatId + " 잠금 획득, 이름 입력 화면으로 이동 (입력 끝날 때까지 다른 사람은 접근 불가)");
        return LockResult.success(seat.getStatus().name(), token);
    }

    /** 이름을 입력하고 Enter를 누른 순간 — 예약을 확정하고 잠금을 해제한다.
     * 소유권은 토큰으로 확인하므로, 입력창에서 이름을 고쳐 써도(최종 이름 ≠ 잠글 때 이름) 문제없다. */
    public ReserveResult confirm(int seatId, String user, String token) {
        Seat seat = seats.get(seatId);
        if (seat == null) {
            return ReserveResult.fail("존재하지 않는 좌석입니다", "UNKNOWN");
        }
        if (token == null || !token.equals(heldToken.get(seatId))) {
            log(user + " → 좌석 " + seatId + " 확정 실패 (잠금이 만료되었거나 본인 소유가 아님)");
            return ReserveResult.fail("잠금이 만료되었거나 이 좌석의 예약 권한이 없습니다", seat.getStatus().name());
        }

        seat.setStatus(Seat.Status.OCCUPIED);
        seat.setOccupiedBy(user);
        seat.incrementVersion();
        releaseHeld(seatId);
        log(user + " → 좌석 " + seatId + " 예약 확정 (비관적 락, 잠금 해제)");
        return ReserveResult.success(seat.getStatus().name());
    }

    /** 입력 화면에서 취소했을 때 — 예약하지 않고 좌석을 그대로 풀어준다. */
    public void cancelLock(int seatId, String user, String token) {
        if (token != null && token.equals(heldToken.get(seatId))) {
            Seat seat = seats.get(seatId);
            if (seat != null) {
                seat.setStatus(Seat.Status.AVAILABLE);
            }
            releaseHeld(seatId);
            log(user + " → 좌석 " + seatId + " 입력 취소, 잠금 해제");
        }
    }

    private void releaseHeld(int seatId) {
        heldToken.remove(seatId);
        heldByDisplay.remove(seatId);
        heldSince.remove(seatId);
        attempts.removeIf(a -> a.getSeatId() == seatId);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
