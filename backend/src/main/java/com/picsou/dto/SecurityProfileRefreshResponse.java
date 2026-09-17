package com.picsou.dto;

/**
 * The outcome of asking for a profile refresh.
 *
 * @param queuedTickers how many distinct securities the pass will consider
 * @param alreadyRunning true when a pass was already in flight and this call did nothing, so the
 *                       UI can say "already under way" rather than claiming it started something
 */
public record SecurityProfileRefreshResponse(int queuedTickers, boolean alreadyRunning) {}
