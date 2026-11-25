package com.ddf.vodsystem.clip;

import com.ddf.vodsystem.user.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;


import java.time.Instant;

@Entity
@Table(name = "clips")
@Data
public class Clip {
    @Id
    @Column(name = "id", nullable = false, unique = true)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "width", nullable = false)
    private Integer width;

    @Column(name = "height", nullable = false)
    private Integer height;

    @Column(name = "fps", nullable = false)
    private Float fps;

    @Column(name = "duration", nullable = false)
    private Float duration;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "video_path", nullable = false, length = 255)
    private String videoPath;

    @Column(name = "thumbnail_path", nullable = false, length = 255)
    private String thumbnailPath;
}
