package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.route.dto.FavoriteRequest;
import watoo.grd.nextroute.application.route.exception.FavoriteConflictException;
import watoo.grd.nextroute.domain.route.favorite.entity.FavoriteRoute;
import watoo.grd.nextroute.domain.route.favorite.entity.FavoriteType;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FavoriteRouteServiceTest {

    @Mock
    watoo.grd.nextroute.domain.route.favorite.service.FavoriteRouteService favoriteDomainService;
    @Mock
    UserRepository userRepository;
    @InjectMocks
    FavoriteRouteService service;

    private static FavoriteRequest request(FavoriteType type) {
        FavoriteRequest req = new FavoriteRequest();
        setField(req, "type", type);
        setField(req, "name", "우리집");
        setField(req, "address", "서울시 강남구");
        setField(req, "endPlace", "집");
        setField(req, "ex", 127.1);
        setField(req, "ey", 37.5);
        return req;
    }

    private static void setField(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void add_homeWhenActiveHomeExists_throwsConflict() {
        User user = new User(123L);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        given(favoriteDomainService.existsActiveType(user, FavoriteType.HOME)).willReturn(true);

        assertThatThrownBy(() -> service.add(7L, request(FavoriteType.HOME)))
                .isInstanceOf(FavoriteConflictException.class);

        verify(favoriteDomainService, never()).save(any());
    }

    @Test
    void add_etcAlwaysAllowed_savesWithoutUniquenessCheck() {
        User user = new User(123L);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        given(favoriteDomainService.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.add(7L, request(FavoriteType.ETC));

        verify(favoriteDomainService, never()).existsActiveType(any(), any());
        verify(favoriteDomainService).save(any(FavoriteRoute.class));
    }

    @Test
    void add_homeWhenNoneExists_saves() {
        User user = new User(123L);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        given(favoriteDomainService.existsActiveType(user, FavoriteType.HOME)).willReturn(false);
        given(favoriteDomainService.save(any())).willAnswer(inv -> inv.getArgument(0));

        var res = service.add(7L, request(FavoriteType.HOME));

        assertThat(res.getName()).isEqualTo("우리집");
        assertThat(res.getAddress()).isEqualTo("서울시 강남구");
        verify(favoriteDomainService).save(any(FavoriteRoute.class));
    }
}
