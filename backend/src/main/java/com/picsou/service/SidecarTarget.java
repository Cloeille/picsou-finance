package com.picsou.service;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * One row of the sidecar registry: name, expected base image (null = reachability only),
 * where to probe, and whether the household uses it.
 */
record SidecarTarget(String name, String expectedBase, Supplier<String> url, LongSupplier sessions) {}
