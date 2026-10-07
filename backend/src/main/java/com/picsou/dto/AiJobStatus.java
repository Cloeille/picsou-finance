package com.picsou.dto;

/**
 * Snapshot of an AI categorization job for one member.
 *
 * @param running   true while the background job is still in progress
 * @param total     total transactions to process in this run
 * @param processed number of transactions processed so far
 * @param applied   number of transactions that were auto-categorized
 * @param suggested number of transactions that received a pending suggestion
 * @param done      true once the job finished (success or error)
 * @param error     set when the job terminated with an unhandled exception
 */
public record AiJobStatus(
    boolean running,
    int total,
    int processed,
    int applied,
    int suggested,
    boolean done,
    String error
) {}
