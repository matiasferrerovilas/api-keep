package api.m2.file.record;

import lombok.Builder;

@Builder
public record WorkspaceUsageResponse(
        long usedBytes,
        long quotaBytes
) {
}
