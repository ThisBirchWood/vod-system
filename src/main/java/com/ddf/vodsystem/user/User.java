package com.ddf.vodsystem.user;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Entity
@Table(name = "users")
@Data
public class User {
    @Id
    @Column(name = "id", nullable = false, unique = true)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "google_id", nullable = false, unique = true)
    private String googleId;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "profile_picture_url")
    private String profilePictureUrl;

    @Column(name = "role", nullable = false)
    private Integer role; // 0: user, 1: admin

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "stream_key")
    private String streamKey;
}

