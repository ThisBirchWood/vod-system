package com.ddf.vodsystem.integration;

import com.ddf.vodsystem.clip.Clip;
import com.ddf.vodsystem.user.User;
import com.ddf.vodsystem.vod.Vod;
import com.ddf.vodsystem.media.ThumbnailService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ThumbnailIT extends MediaTestSupport {

    @Test
    void clipThumbnailIsValidPngWithVideoDimensions() throws Exception {
        User user = createUser();

        assertSucceeded(compress(source(), options(0, 2), user));
        Clip clip = awaitSingleClip(user);

        BufferedImage image = awaitThumbnail(clip.getThumbnailPath());
        assertThat(image.getWidth()).isEqualTo(clip.getWidth());
        assertThat(image.getHeight()).isEqualTo(clip.getHeight());
    }

    @Test
    void vodThumbnailIsValidPngWithVideoDimensions() throws Exception {
        User user = createUser();
        Instant first = writeStreamSegments(user, 6);

        assertSucceeded(saveSection(user, first, first.plusSeconds(6)));
        Vod vod = awaitSingleVod(user);

        BufferedImage image = awaitThumbnail(vod.getThumbnailPath());
        assertThat(image.getWidth()).isEqualTo(vod.getWidth()).isEqualTo(320);
        assertThat(image.getHeight()).isEqualTo(vod.getHeight()).isEqualTo(180);
    }

    @Test
    void clipThumbnailEndpointServesImage() throws Exception {
        User user = createUser();
        assertSucceeded(compress(source(), options(0, 2), user));
        Clip clip = awaitSingleClip(user);
        awaitThumbnail(clip.getThumbnailPath());

        byte[] body = mockMvc.perform(get("/api/v1/clips/" + clip.getId() + "/thumbnail").with(authenticatedAs(user)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(body))).isNotNull();
    }

    @Test
    void vodThumbnailEndpointServesImage() throws Exception {
        User user = createUser();
        Instant first = writeStreamSegments(user, 6);
        assertSucceeded(saveSection(user, first, first.plusSeconds(6)));
        Vod vod = awaitSingleVod(user);
        awaitThumbnail(vod.getThumbnailPath());

        byte[] body = mockMvc.perform(get("/api/v1/vods/" + vod.getId() + "/thumbnail").with(authenticatedAs(user)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(body))).isNotNull();
    }

    @Test
    void failedThumbnailStillPersistsClipAndEndpointReturns404() throws Exception {
        // The injected bean is the @Async proxy; stub the spy behind it
        ThumbnailService spy = AopTestUtils.getUltimateTargetObject(thumbnailService);
        doReturn(CompletableFuture.failedFuture(new IOException("simulated ffmpeg failure")))
                .when(spy).createThumbnail(any(), any(), any());
        User user = createUser();

        assertSucceeded(compress(source(), options(0, 2), user));
        Clip clip = awaitSingleClip(user);

        assertThat(directoryService.resolvePath(clip.getVideoPath())).isRegularFile();
        assertThat(directoryService.resolvePath(clip.getThumbnailPath())).doesNotExist();
        mockMvc.perform(get("/api/v1/clips/" + clip.getId() + "/thumbnail").with(authenticatedAs(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is("error")));
    }
}
