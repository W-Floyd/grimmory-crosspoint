package org.booklore.model.dto.overdrive;

/**
 * The result of resolving an OverDrive library key against the Thunder library directory
 * ({@code /v2/libraries/{key}}). Used to validate the admin-configured {@code libraryKey} and echo the
 * library's display name back to the operator.
 *
 * @param valid      whether the key resolved to a real library
 * @param libraryKey the key that was resolved (echoed back)
 * @param name       the resolved library display name, or null when the key did not resolve
 */
public record OverDriveLibraryResolution(boolean valid, String libraryKey, String name) {}
