package com.project.souklab.dao;

import com.project.souklab.model.ClientSubscription;
import com.project.souklab.model.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface ClientSubscriptionRepository extends JpaRepository<ClientSubscription, String> {
    Optional<ClientSubscription> findFirstByAccountIdAndStatusInOrderByCreatedAtDesc(String accountId, List<SubscriptionStatus> statuses);
    List<ClientSubscription> findByAccountIdInAndStatusInOrderByCreatedAtDesc(Collection<String> accountIds, List<SubscriptionStatus> statuses);
    List<ClientSubscription> findByAccountIdOrderByCreatedAtDesc(String accountId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ClientSubscription s where s.id = :id")
    Optional<ClientSubscription> findWithLockById(@Param("id") String id);
    List<ClientSubscription> findByStatusAndExpiresAtBefore(SubscriptionStatus status, LocalDateTime now);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ClientSubscription> findByStatusAndExpiresAtBefore(SubscriptionStatus status, LocalDateTime now, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ClientSubscription> findByStatusAndExpiresAtAfterAndExpiresAtBefore(SubscriptionStatus status, LocalDateTime after, LocalDateTime before, Pageable pageable);
    long countByAccountIdAndStatus(String accountId, SubscriptionStatus status);
    long countByStatus(SubscriptionStatus status);
    long countByStatusAndCreatedAtBetween(SubscriptionStatus status, LocalDateTime from, LocalDateTime to);
    long countByStatusAndCreatedAtBetweenAndDeletedAtIsNull(SubscriptionStatus status, LocalDateTime from, LocalDateTime to);
}
