package com.ddf.vodsystem.marker;

import com.ddf.vodsystem.stream.Stream;
import com.ddf.vodsystem.user.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Entity
@Table(name = "markers")
@Data
public class Marker {
    @Id
    @Column(name = "id", nullable = false, unique = true)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    @JoinColumn(name = "stream_id", nullable = false)
    private Stream stream;

    @Column(name = "message", length = 255)
    private String message;

    @Column(name = "timestamp")
    private Instant timestamp;
}
