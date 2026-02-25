package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link ApiCallLogEntity}.
 */
public interface ApiCallLogRepository extends JpaRepository<ApiCallLogEntity, Long> {

    /**
     * Finds all API calls for a given job run, ordered by call time ascending.
     *
     * @param jobRunId the job run ID to filter by
     * @return list of API call logs
     */
    List<ApiCallLogEntity> findByJobRunIdOrderByCalledAtAsc(Long jobRunId);

    /**
     * Finds recent API calls for a given service, ordered descending.
     *
     * @param service the service name to filter by
     * @param pageable pagination configuration
     * @return list of API call logs
     */
    List<ApiCallLogEntity> findByServiceOrderByCalledAtDesc(ServiceName service, Pageable pageable);

    /**
     * Counts failed API calls for a given service.
     *
     * @param service the service name to filter by
     * @return count of failed calls
     */
    long countByServiceAndSucceededIsFalse(ServiceName service);
}
