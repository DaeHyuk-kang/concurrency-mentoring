package com.example.seatlock;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class SeatController {

    private final SeatService seatService;

    public SeatController(SeatService seatService) {
        this.seatService = seatService;
    }

    public record ReserveRequest(int seatId, String user, String mode) {}

    private static final Set<String> VALID_MODES = Set.of("buggy", "optimistic", "fixed");

    @GetMapping("/seats")
    public List<Seat> seats() {
        return seatService.getSeats();
    }

    @GetMapping("/log")
    public List<String> log() {
        return seatService.getLog();
    }

    @GetMapping("/attempts")
    public List<Attempt> attempts() {
        return seatService.getAttempts();
    }

    @PostMapping("/reserve")
    public ReserveResult reserve(@RequestBody ReserveRequest req) {
        if (req.mode() == null || !VALID_MODES.contains(req.mode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "mode는 buggy, optimistic, fixed 중 하나여야 합니다 (받은 값: " + req.mode() + ")");
        }
        return switch (req.mode()) {
            case "fixed" -> seatService.reserveFixed(req.seatId(), req.user());
            case "optimistic" -> seatService.reserveOptimistic(req.seatId(), req.user());
            default -> seatService.reserveBuggy(req.seatId(), req.user());
        };
    }

    @PostMapping("/reset")
    public Map<String, String> reset() {
        seatService.reset();
        return Map.of("status", "ok");
    }

    public record SeatUserRequest(int seatId, String user) {}
    public record SeatTokenRequest(int seatId, String user, String token) {}

    @PostMapping("/lock")
    public LockResult lock(@RequestBody SeatUserRequest req) {
        return seatService.lockForInput(req.seatId(), req.user());
    }

    @PostMapping("/confirm")
    public ReserveResult confirm(@RequestBody SeatTokenRequest req) {
        return seatService.confirm(req.seatId(), req.user(), req.token());
    }

    @PostMapping("/cancel")
    public Map<String, String> cancel(@RequestBody SeatTokenRequest req) {
        seatService.cancelLock(req.seatId(), req.user(), req.token());
        return Map.of("status", "ok");
    }
}
