package com.ddf.vodsystem.integration;

import com.ddf.vodsystem.clip.Clip;
import com.ddf.vodsystem.user.User;
import com.ddf.vodsystem.vod.Vod;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression: deleting a clip or VoD used to leave its files on disk, because the stored paths
 * (relative to the users media root) were resolved against the process working directory.
 */
class MediaDeletionIT extends MediaTestSupport {

    @Test
    void deletingClipRemovesVideoAndThumbnailFromDisk() throws Exception {
        User user = createUser();
        assertSucceeded(compress(source(), options(0, 2), user));
        Clip clip = awaitSingleClip(user);
        // Wait for the async thumbnail, otherwise it could be written after the delete
        awaitThumbnail(clip.getThumbnailPath());

        Path video = directoryService.resolvePath(clip.getVideoPath());
        Path thumbnail = directoryService.resolvePath(clip.getThumbnailPath());
        assertThat(video).isRegularFile();
        assertThat(thumbnail).isRegularFile();

        mockMvc.perform(delete("/api/v1/clips/" + clip.getId()).with(authenticatedAs(user)))
                .andExpect(status().isOk());

        assertThat(clipRepository.findById(clip.getId())).isEmpty();
        assertThat(video).doesNotExist();
        assertThat(thumbnail).doesNotExist();
    }

    @Test
    void deletingVodRemovesVideoAndThumbnailFromDisk() throws Exception {
        User user = createUser();
        Instant first = writeStreamSegments(user, 6);
        assertSucceeded(saveSection(user, first, first.plusSeconds(6)));
        Vod vod = awaitSingleVod(user);
        awaitThumbnail(vod.getThumbnailPath());

        Path video = directoryService.resolvePath(vod.getVideoPath());
        Path thumbnail = directoryService.resolvePath(vod.getThumbnailPath());
        assertThat(video).isRegularFile();
        assertThat(thumbnail).isRegularFile();

        mockMvc.perform(delete("/api/v1/vods/" + vod.getId()).with(authenticatedAs(user)))
                .andExpect(status().isOk());

        assertThat(vodRepository.findById(vod.getId())).isEmpty();
        assertThat(video).doesNotExist();
        assertThat(thumbnail).doesNotExist();
    }

    @Test
    void deletingOneClipLeavesOtherClipsFilesIntact() throws Exception {
        User user = createUser();
        assertSucceeded(compress(source(), options(0, 2), user));
        Clip deleted = awaitSingleClip(user);
        awaitThumbnail(deleted.getThumbnailPath());

        assertSucceeded(compress(small(), options(0, 2), user));
        await("a second clip", () -> clipRepository.findByUser(user).size() == 2);
        Clip kept = clipRepository.findByUser(user).stream()
                .filter(c -> !c.getId().equals(deleted.getId()))
                .findFirst().orElseThrow();
        awaitThumbnail(kept.getThumbnailPath());

        mockMvc.perform(delete("/api/v1/clips/" + deleted.getId()).with(authenticatedAs(user)))
                .andExpect(status().isOk());

        assertThat(directoryService.resolvePath(kept.getVideoPath())).isRegularFile();
        assertThat(directoryService.resolvePath(kept.getThumbnailPath())).isRegularFile();
    }
}
