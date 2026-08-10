package watoo.grd.nextroute.common.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FCM 자격증명 초기화. {@code journey.notification.sender=fcm} 일 때만 돈다.
 *
 * <p>기본값({@code noop})에서는 이 설정 자체가 뜨지 않는다 — 자격증명 없이 개발·테스트가 그대로
 * 돌아가야 한다.
 *
 * <p><b>초기화가 실패하면 부팅을 막는다.</b> 켠다고 해 놓고 조용히 아무것도 안 보내는 상태가
 * 가장 나쁘다. 같은 이유로 relay 를 켠 채 sender 가 noop 인 조합도 부팅에서 막는다
 * ({@link JourneyNotificationProperties}).
 *
 * <p>서비스 계정 JSON 은 <b>worker 컨테이너에만</b> 읽기 전용으로 마운트한다. relay 는 worker
 * 프로필 전용이라 API 에 자격증명을 주면 권한만 넓어지고, 초기화 실패가 API 부팅까지 막는다.
 *
 * <p>불변식: <b>배포 하나 = Firebase 프로젝트 하나</b>. {@code user_push_token.environment} 로
 * 프로젝트를 갈라 보내지 않는다 — 그 값은 클라이언트가 주는 것이라 라우팅 근거가 못 된다.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "journey.notification", name = "sender", havingValue = "fcm")
public class FirebaseConfig {

    @Bean
    public FirebaseApp journeyFirebaseApp(JourneyNotificationProperties properties)
            throws IOException {
        Path credentials = Path.of(properties.getFcm().getCredentialsPath());
        if (!Files.isReadable(credentials)) {
            throw new IllegalStateException(
                    "FCM 자격증명을 읽을 수 없다: " + credentials + ". 볼륨 마운트를 확인한다");
        }
        try (InputStream in = Files.newInputStream(credentials)) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(in))
                    .build();
            // 같은 JVM 에서 두 번 초기화하면 SDK 가 IllegalStateException 을 던진다. 테스트가
            // 컨텍스트를 여러 벌 띄우므로 이미 있으면 그것을 쓴다.
            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                    ? FirebaseApp.initializeApp(options)
                    : FirebaseApp.getInstance();
            log.info("[JourneyNotification] FCM 초기화 완료 (credentials={})", credentials);
            return app;
        }
    }

    @Bean
    public FirebaseMessaging firebaseMessaging(FirebaseApp journeyFirebaseApp) {
        return FirebaseMessaging.getInstance(journeyFirebaseApp);
    }
}
