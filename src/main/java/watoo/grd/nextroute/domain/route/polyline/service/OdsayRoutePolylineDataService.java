package watoo.grd.nextroute.domain.route.polyline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.route.dto.OdsayRoutePolylineData;
import watoo.grd.nextroute.domain.route.polyline.entity.CollectionJobStatus;
import watoo.grd.nextroute.domain.route.polyline.entity.OdsayRoutePolyline;
import watoo.grd.nextroute.domain.route.polyline.entity.OdsayRoutePolylineCollectionJob;
import watoo.grd.nextroute.domain.route.polyline.entity.OdsayRouteLineSeed;
import watoo.grd.nextroute.domain.route.polyline.repository.OdsayRouteLineSeedRepository;
import watoo.grd.nextroute.domain.route.polyline.repository.OdsayRoutePolylineCollectionJobRepository;
import watoo.grd.nextroute.domain.route.polyline.repository.OdsayRoutePolylineRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OdsayRoutePolylineDataService {

    private final OdsayRoutePolylineRepository polylineRepository;
    private final OdsayRouteLineSeedRepository seedRepository;
    private final OdsayRoutePolylineCollectionJobRepository jobRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Optional<OdsayRoutePolylineData> findPolyline(String routeId, int laneClass) {
        return polylineRepository.findByOdsayRouteIdAndLaneClass(routeId, laneClass)
                .map(entity -> deserialize(entity.getPolyline()));
    }

    @Transactional
    public void saveOrUpdatePolyline(String routeId, int laneClass, Integer laneType,
                                     OdsayRoutePolylineData data, String sourceMapObject) {
        String json = serialize(data);
        OffsetDateTime now = OffsetDateTime.now();

        polylineRepository.findByOdsayRouteIdAndLaneClass(routeId, laneClass)
                .ifPresentOrElse(
                        existing -> existing.update(json, data.size(), sourceMapObject, now),
                        () -> polylineRepository.save(OdsayRoutePolyline.builder()
                                .odsayRouteId(routeId)
                                .laneClass(laneClass)
                                .laneType(laneType)
                                .pointCount(data.size())
                                .sourceMapObject(sourceMapObject)
                                .polyline(json)
                                .fetchedAt(now)
                                .updatedAt(now)
                                .build())
                );
    }

    @Transactional
    public void requestCollection(String routeId, int laneClass, String mapObjFragment) {
        OffsetDateTime now = OffsetDateTime.now();
        jobRepository.findByOdsayRouteIdAndLaneClass(routeId, laneClass)
                .ifPresentOrElse(
                        job -> job.incrementRequest(mapObjFragment, now),
                        () -> jobRepository.save(OdsayRoutePolylineCollectionJob.builder()
                                .odsayRouteId(routeId)
                                .laneClass(laneClass)
                                .status(CollectionJobStatus.PENDING)
                                .requestedCount(1)
                                .lastMapObjFragment(mapObjFragment)
                                .requestedAt(now)
                                .build())
                );
    }

    @Transactional(readOnly = true)
    public List<OdsayRoutePolylineCollectionJob> findPendingJobs(int limit) {
        return jobRepository.findByStatusOrderByRequestedAtAsc(
                CollectionJobStatus.PENDING, PageRequest.of(0, limit));
    }

    @Transactional
    public boolean markJobRunning(Long jobId) {
        return jobRepository.findById(jobId)
                .map(job -> {
                    job.markRunning(OffsetDateTime.now());
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public void markJobSuccess(Long jobId) {
        jobRepository.findById(jobId).ifPresent(job -> job.markSuccess(OffsetDateTime.now()));
    }

    @Transactional
    public void markJobFailed(Long jobId, String error) {
        jobRepository.findById(jobId).ifPresent(job -> job.markFailed(error, OffsetDateTime.now()));
    }

    @Transactional(readOnly = true)
    public List<OdsayRouteLineSeed> findEnabledSeeds() {
        return seedRepository.findByEnabledTrue();
    }

    @Transactional(readOnly = true)
    public List<OdsayRoutePolyline> findAllPolylines() {
        return polylineRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<OdsayRoutePolylineCollectionJob> findAllJobs() {
        return jobRepository.findAll();
    }

    @Transactional
    public void retryJob(Long jobId) {
        jobRepository.findById(jobId).ifPresent(job -> {
            if (job.getStatus() == CollectionJobStatus.FAILED) {
                job.incrementRequest(job.getLastMapObjFragment(), OffsetDateTime.now());
            }
        });
    }

    private String serialize(OdsayRoutePolylineData data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize polyline data", e);
        }
    }

    private OdsayRoutePolylineData deserialize(String json) {
        try {
            return objectMapper.readValue(json, OdsayRoutePolylineData.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize polyline data", e);
        }
    }
}
