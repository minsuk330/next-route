package watoo.grd.nextroute.domain.user.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserDomainServiceTest {

    @Mock UserRepository userRepository;
    @InjectMocks UserDomainService userDomainService;

    @Test
    void findOrCreate_existingUser_returnsWithoutSave() {
        User existing = User.builder().deviceId("device-1").build();
        given(userRepository.findByDeviceId("device-1")).willReturn(Optional.of(existing));

        User result = userDomainService.findOrCreate("device-1");

        assertThat(result.getDeviceId()).isEqualTo("device-1");
        verify(userRepository, never()).save(any());
    }

    @Test
    void findOrCreate_newDevice_savesAndReturns() {
        User saved = User.builder().deviceId("new-device").build();
        given(userRepository.findByDeviceId("new-device")).willReturn(Optional.empty());
        given(userRepository.save(any())).willReturn(saved);

        User result = userDomainService.findOrCreate("new-device");

        assertThat(result.getDeviceId()).isEqualTo("new-device");
        verify(userRepository).save(any());
    }
}
