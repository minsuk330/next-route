package watoo.grd.nextroute.domain.user.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserDomainServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserDomainService userDomainService;

    @Test
    void findOrCreate_existingUser_returnsWithoutSave() {
        User existing = User.builder().deviceId("existing-device").build();
        when(userRepository.findByDeviceId("existing-device")).thenReturn(Optional.of(existing));

        User result = userDomainService.findOrCreate("existing-device");

        assertThat(result.getDeviceId()).isEqualTo("existing-device");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void findOrCreate_newDevice_savesAndReturns() {
        User saved = User.builder().deviceId("new-device").build();
        when(userRepository.findByDeviceId("new-device")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any())).thenReturn(saved);

        User result = userDomainService.findOrCreate("new-device");

        assertThat(result.getDeviceId()).isEqualTo("new-device");
        verify(userRepository).saveAndFlush(argThat(u -> "new-device".equals(u.getDeviceId())));
    }

    @Test
    void findOrCreate_concurrentConflict_retriesAndReturns() {
        User existing = User.builder().deviceId("race-device").build();
        when(userRepository.findByDeviceId("race-device"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique"));

        User result = userDomainService.findOrCreate("race-device");

        assertThat(result.getDeviceId()).isEqualTo("race-device");
    }

    @Test
    void findOrCreate_nullDeviceId_throwsIllegalArgument() {
        assertThatThrownBy(() -> userDomainService.findOrCreate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findOrCreate_blankDeviceId_throwsIllegalArgument() {
        assertThatThrownBy(() -> userDomainService.findOrCreate("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findOnly_existingUser_returnsUser() {
        User user = User.builder().deviceId("device-1").build();
        when(userRepository.findByDeviceId("device-1")).thenReturn(Optional.of(user));

        Optional<User> result = userDomainService.findOnly("device-1");

        assertThat(result).isPresent();
        assertThat(result.get().getDeviceId()).isEqualTo("device-1");
    }

    @Test
    void findOnly_unknownDevice_returnsEmpty() {
        when(userRepository.findByDeviceId("unknown")).thenReturn(Optional.empty());

        Optional<User> result = userDomainService.findOnly("unknown");

        assertThat(result).isEmpty();
    }

    @Test
    void findOnly_blankDeviceId_returnsEmpty() {
        Optional<User> result = userDomainService.findOnly("  ");

        assertThat(result).isEmpty();
        verify(userRepository, never()).findByDeviceId(any());
    }
}
