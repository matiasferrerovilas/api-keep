package api.m2.file.mappers;

import api.m2.file.entity.FileActivity;
import api.m2.file.record.FileActivityResponse;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FileActivityMapper {

    FileActivityResponse toResponse(FileActivity activity);
}
