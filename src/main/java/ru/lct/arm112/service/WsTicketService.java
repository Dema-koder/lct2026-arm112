package ru.lct.arm112.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WsTicketService {
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public Ticket issue(String subject) {
        Instant expiresAt = Instant.now().plusSeconds(30);
        Ticket ticket = new Ticket(UUID.randomUUID().toString(), subject, expiresAt);
        tickets.put(ticket.value(), ticket);
        return ticket;
    }

    public Ticket consume(String value) {
        Ticket ticket = tickets.remove(value);
        if (ticket == null || ticket.expiresAt().isBefore(Instant.now())) {
            return null;
        }
        return ticket;
    }

    public record Ticket(String value, String subject, Instant expiresAt) {}
}
