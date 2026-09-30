package com.example.seatlock;

public record ReserveResult(boolean success, String message, String seatStatus) {

    public static ReserveResult success(String seatStatus) {
        return new ReserveResult(true, "예약 성공", seatStatus);
    }

    public static ReserveResult fail(String message, String seatStatus) {
        return new ReserveResult(false, message, seatStatus);
    }
}
