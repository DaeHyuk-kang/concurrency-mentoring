package com.example.seatlock;

public record LockResult(boolean success, String message, String seatStatus, String token) {

    public static LockResult success(String seatStatus, String token) {
        return new LockResult(true, "잠금 획득", seatStatus, token);
    }

    public static LockResult fail(String message, String seatStatus) {
        return new LockResult(false, message, seatStatus, null);
    }
}
