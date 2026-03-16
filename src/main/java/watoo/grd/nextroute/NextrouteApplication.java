package watoo.grd.nextroute;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NextrouteApplication {

	public static void main(String[] args) {
		SpringApplication.run(NextrouteApplication.class, args);
	}

}
