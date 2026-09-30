package com.example.seatlock;

/** 지금 이 순간 어떤 좌석을 누가, 어떤 상태로 건드리고 있는지 보여주기 위한 실시간 표시용 레코드.
 * WAITING = 락을 못 얻어서 줄 서서 기다리는 중 (비관적 락에서만 발생)
 * PROCESSING = 실제로 확인·지연·저장을 진행하는 중 */
public class Attempt {
    private final int seatId;
    private final String user;
    private volatile String state;

    public Attempt(int seatId, String user, String state) {
        this.seatId = seatId;
        this.user = user;
        this.state = state;
    }

    public int getSeatId() { return seatId; }
    public String getUser() { return user; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
}
