package com.ddf.vodsystem.dto;

public class ProgressTracker {
    private float progress = 0.0f;
    private boolean isComplete = false;

    /**
     * Returns the current progress.
     *
     * @return the progress as a fraction in {@code [0, 1]}
     */
    public synchronized float getProgress() {
        return progress;
    }

    /**
     * Returns whether the tracked task has been marked complete.
     *
     * @return {@code true} if complete
     */
    public synchronized boolean isComplete() {
        return isComplete;
    }

    /**
     * Updates the current progress.
     *
     * @param newProgress the progress as a fraction in {@code [0, 1]}
     * @throws IllegalArgumentException if {@code newProgress} is outside {@code [0, 1]}
     */
    public synchronized void setProgress(float newProgress) {
        if (newProgress < 0 || newProgress > 1) {
            throw new IllegalArgumentException("Progress must be between 0 and 1");
        }
        this.progress = newProgress;
    }

    /**
     * Marks the tracked task as complete.
     */
    public synchronized void markComplete() {
        this.isComplete = true;
    }

    /**
     * Resets progress to zero and clears the complete flag.
     */
    public synchronized void reset() {
        this.progress = 0.0f;
        this.isComplete = false;
    }
}
