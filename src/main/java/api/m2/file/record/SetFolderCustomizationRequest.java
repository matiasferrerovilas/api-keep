package api.m2.file.record;

/** Both fields nullable/independent: sending {@code null} for one clears just that one back to
 * the default, without touching the other. */
public record SetFolderCustomizationRequest(String color, String icon) {
}
