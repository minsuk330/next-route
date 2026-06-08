package watoo.grd.nextroute.application.bus.port.out;

import java.time.Instant;
import java.util.Optional;

public interface BusApiBlockStatusPort {

	Optional<Instant> getBlockedUntil();
}
