package api.m2.file.record;

import api.m2.file.enums.FileType;
import java.util.List;
import lombok.Builder;

/**
 * A single match from {@code GET /v1/folders/search}. {@code path} is the breadcrumb of ancestor
 * folder names from the workspace root down to (but not including) this node — cheap to compute
 * server-side since the full workspace tree is already loaded to answer the search, so the
 * frontend doesn't have to walk {@code parentId} itself to show where a result lives.
 */
@Builder
public record FileSearchResult(
        String id,
        String name,
        FileType type,
        String parentId,
        List<String> path
) {
}
