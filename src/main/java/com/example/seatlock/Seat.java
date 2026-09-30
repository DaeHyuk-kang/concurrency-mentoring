package com.example.seatlock;

public class Seat {

    public enum Status { AVAILABLE, PROCESSING, OCCUPIED }

    private final int id;
    private volatile Status status = Status.AVAILABLE;
    private volatile String occupiedBy;
    private volatile int version = 0;

    public Seat(int id) {
        this.id = id;
    }

    public int getVersion() {
        return version;
    }

    public void incrementVersion() {
        this.version++;
    }

    public int getId() {
        return id;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getOccupiedBy() {
        return occupiedBy;
    }

    public void setOccupiedBy(String occupiedBy) {
        this.occupiedBy = occupiedBy;
    }

    public void reset() {
        this.status = Status.AVAILABLE;
        this.occupiedBy = null;
        this.version = 0;
    }
}
