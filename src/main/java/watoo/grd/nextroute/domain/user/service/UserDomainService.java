package watoo.grd.nextroute.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserDomainService {

    private final UserRepository userRepository;

    @Transactional
    public User findOrCreate(String deviceId) {
        return userRepository.findByDeviceId(deviceId)
                .orElseGet(() -> userRepository.save(
                        User.builder().deviceId(deviceId).build()
                ));
    }
}
