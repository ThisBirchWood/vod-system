package com.ddf.vodsystem.job;

import com.ddf.vodsystem.common.dto.APIResponse;
import com.ddf.vodsystem.clip.ClipNotFoundException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/v1/jobs")
public class JobController {
    private final JobRegistryService jobRegistryService;
    private static final String SUCCESS = "success";
    private static final String FILENAME_HEADER = "inline; filename=\"%s\"";

    public JobController(JobRegistryService jobRegistryService){
        this.jobRegistryService = jobRegistryService;
    }

    /**
     * Retrieves the status and progress of an asynchronous job.
     *
     * @param uuid the UUID of the job to look up
     * @return {@code 200 OK} wrapping the job's state as a {@link JobResponseDTO} DTO
     * @throws JobNotFoundException if no job with {@code uuid} exists
     */
    @GetMapping("/{uuid}")
    public ResponseEntity<APIResponse<JobResponseDTO>> getJob(@PathVariable String uuid) {
        Job job = jobRegistryService.getJob(uuid);
        JobResponseDTO jobResponse = new JobResponseDTO(
                job.getUuid(),
                job.getProgressTracker().getProgress(),
                job.getProgressTracker().isComplete(),
                job.getState(),
                job.getErrorOutput(),
                job.getCreatedAt()
        );

        return ResponseEntity.ok(new APIResponse<>(
                SUCCESS,
                "Job successfully retreived",
                jobResponse
        ));
    }

    /**
     * Downloads the output file produced by a completed job.
     *
     * @param uuid the UUID of the job whose output to download
     * @return {@code 200 OK} with the output file as a downloadable resource
     * @throws JobNotFoundException     if no job with {@code uuid} exists
     * @throws JobNotFinishedException if the job has not yet produced a downloadable file
     * @throws ClipNotFoundException     if the job's output file no longer exists on disk
     */
    @GetMapping("/{uuid}/download")
    public ResponseEntity<Resource> downloadJobFile(@PathVariable String uuid) {
        Job job = jobRegistryService.getJob(uuid);

        if (job.getDownload() == null) {
            throw new JobNotFinishedException("Job " + uuid + " not ready for download.");
        }

        Resource resource = new FileSystemResource(job.getDownload());

        if (!resource.exists()) {
            throw new ClipNotFoundException("Job download file " + job.getDownload() + " does not exist.");
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, String.format(FILENAME_HEADER, resource.getFilename()))
                .contentType(MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM))
                .body(resource);
    }

}
