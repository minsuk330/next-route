package watoo.grd.nextroute.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserDomainService {

    private final UserRepository userRepository;

    public Optional<User> findOnly(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByDeviceId(deviceId);
    }

    @Transactional
    public User findOrCreate(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be null or blank");
        }
        try {
            return userRepository.findByDeviceId(deviceId)
                    .orElseGet(() -> userRepository.saveAndFlush(
                            User.builder().deviceId(deviceId).build()));
        } catch (DataIntegrityViolationException e) {
            return userRepository.findByDeviceId(deviceId)
                    .orElseThrow(() -> new IllegalStateException("User not found after conflict", e));
        }
    }
}
