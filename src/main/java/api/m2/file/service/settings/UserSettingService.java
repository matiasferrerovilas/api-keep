package api.m2.file.service.settings;

import api.m2.file.entity.UserSetting;
import api.m2.file.enums.UserSettingKey;
import api.m2.file.exceptions.EntityNotFoundException;
import api.m2.file.record.settings.UserSettingResponse;
import api.m2.file.repository.UserSettingRepository;
import api.m2.file.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class UserSettingService {

    private final UserSettingRepository userSettingRepository;
    private final UserService userService;

    public UserSettingResponse getByKey(UserSettingKey key) {
        Long userId = userService.getMe().id();
        return userSettingRepository.findByUserIdAndSettingKey(userId, key)
                .map(s -> new UserSettingResponse(s.getSettingKey(), s.getSettingValue()))
                .orElseThrow(() -> new EntityNotFoundException(
                        "No se encontró un default configurado para: " + key.name()));
    }

    @Transactional
    public UserSettingResponse upsert(UserSettingKey key, Long value) {
        Long userId = userService.getMe().id();
        UserSetting setting = userSettingRepository.findByUserIdAndSettingKey(userId, key)
                .orElseGet(() -> UserSetting.builder()
                        .userId(userId)
                        .settingKey(key)
                        .build());
        setting.setSettingValue(value);
        UserSetting saved = userSettingRepository.saveAndFlush(setting);
        return new UserSettingResponse(saved.getSettingKey(), saved.getSettingValue());
    }

    @Transactional
    public void upsertForUser(Long userId, UserSettingKey key, Long value) {
        userSettingRepository.upsertSetting(userId, key.name(), value);
    }

    @Transactional
    public void deleteByKey(UserSettingKey key) {
        Long userId = userService.getMe().id();
        userSettingRepository.deleteByUserIdAndSettingKey(userId, key);
    }

    @Transactional
    public void deleteByKeyForUser(Long userId, UserSettingKey key) {
        userSettingRepository.deleteByUserIdAndSettingKey(userId, key);
    }

    public Optional<Long> getDefaultWorkspaceId(Long userId) {
        return userSettingRepository.findByUserIdAndSettingKey(userId, UserSettingKey.DEFAULT_WORKSPACE)
                .map(UserSetting::getSettingValue);
    }
}
