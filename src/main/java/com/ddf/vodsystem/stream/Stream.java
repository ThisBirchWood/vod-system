package com.ddf.vodsystem.stream;

import com.ddf.vodsystem.user.User;
import jakarta.persistence.*;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

@Entity
@Table(name = "streams")
@Data
public class Stream {
    @Id
    @Column(name = "id", nullable = false, unique = true)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "start_time")
    private Instant startDate;

    @Column(name = "end_time")
    private Instant endDate;

    @Column(name = "last_seen")
    private Instant lastSeen;
}
