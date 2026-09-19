package com.relay.engine.queue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Durable mirror of a queued unit of work for a run. Redis is the primary dispatch queue; this
 * table makes jobs survive a restart, carries the {@code available_at} time for delayed work, and
 * a lease ({@code leased_until}) so a crashed worker's job can be reclaimed. Wired up in Phase 4/6.
 */
@Entity
@Table(name = "queue_job")
public class QueueJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QueueJobStatus status = QueueJobStatus.ready;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "leased_until")
    private Instant leasedUntil;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected QueueJob() {
    }

    public QueueJob(String runId, Instant availableAt) {
        this.runId = runId;
        this.availableAt = availableAt;
        this.status = QueueJobStatus.ready;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public QueueJobStatus getStatus() {
        return status;
    }

    public void setStatus(QueueJobStatus status) {
        this.status = status;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public void setAvailableAt(Instant availableAt) {
        this.availableAt = availableAt;
    }

    public Instant getLeasedUntil() {
        return leasedUntil;
    }

    public void setLeasedUntil(Instant leasedUntil) {
        this.leasedUntil = leasedUntil;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
