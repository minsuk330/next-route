package watoo.grd.nextroute.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * worker 프로필에서 살아나는 컴포넌트가 worker 에서 <b>안 만들어지는</b> 빈에 기대면 worker 는 부팅하지 못한다.
 *
 * <p>2026-10-10: {@code NationalBusStopTileService} 는 {@code @Profile("!worker")} 인데 그것을 받는
 * {@code MapBusStopController} 에는 조건이 없어 worker 가 {@code APPLICATION FAILED TO START} 로 재시작을 반복했다.
 * api 만 배포하는 동안에는 드러나지 않는다 — 다음 worker 배포 때 처음 죽는다.
 *
 * <p>프로필만 본다. {@code @ConditionalOnProperty} 같은 다른 조건은 여기서 판정하지 않는다.
 */
class WorkerProfileWiringTest {

    private static final String BASE_PACKAGE = "watoo.grd.nextroute";
    private static final Set<String> WORKER_PROFILES = Set.of("prod", "worker");

    @Test
    void workerComponentsOnlyDependOnBeansThatExistInWorker() throws Exception {
        List<Class<?>> components = scanComponents();
        List<String> violations = new ArrayList<>();

        for (Class<?> component : components) {
            if (!activeInWorker(component)) {
                continue;
            }
            for (Constructor<?> constructor : component.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    List<Class<?>> candidates = components.stream()
                            .filter(parameter::isAssignableFrom)
                            .toList();
                    if (!candidates.isEmpty() && candidates.stream().noneMatch(WorkerProfileWiringTest::activeInWorker)) {
                        violations.add(component.getSimpleName() + " → " + parameter.getSimpleName());
                    }
                }
            }
        }

        assertThat(violations)
                .as("worker 에서 만들어지지 않는 빈에 기대는 컴포넌트(같은 @Profile 을 붙일 것)")
                .isEmpty();
    }

    private static boolean activeInWorker(Class<?> type) {
        Profile profile = AnnotatedElementUtils.findMergedAnnotation(type, Profile.class);
        return profile == null || Profiles.of(profile.value()).matches(WORKER_PROFILES::contains);
    }

    private static List<Class<?>> scanComponents() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<Class<?>> types = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> type = ClassUtils.forName(definition.getBeanClassName(), WorkerProfileWiringTest.class.getClassLoader());
            if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
                types.add(type);
            }
        }
        return types;
    }
}
