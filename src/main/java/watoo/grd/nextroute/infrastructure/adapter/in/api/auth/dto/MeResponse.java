package watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto;

import watoo.grd.nextroute.application.auth.dto.MeResult;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;

import java.time.LocalDateTime;
import java.util.List;

/** 내 정보 응답. */
public record MeResponse(
        long userId,
        String displayName,
        String email,
        List<LinkedIdentity> identities
) {

    public record LinkedIdentity(AuthProvider provider, LocalDateTime linkedAt) { }

    public static MeResponse from(MeResult r) {
        return new MeResponse(
                r.userId(),
                r.displayName(),
                r.email(),
                r.identities().stream()
                        .map(i -> new LinkedIdentity(i.provider(), i.linkedAt()))
                        .toList());
    }
}
