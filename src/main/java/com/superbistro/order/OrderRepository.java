package com.superbistro.order;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<CustomerOrder, UUID> {

    @Query("select coalesce(max(o.orderNo), 0) from CustomerOrder o where o.restaurantId = :r and o.businessDay = :day")
    int maxOrderNo(@Param("r") UUID restaurantId, @Param("day") LocalDate day);

    @Query("""
        select distinct o from CustomerOrder o left join fetch o.items
        where o.restaurantId = :r and o.createdAt >= :from and o.createdAt < :to
        order by o.createdAt desc
        """)
    List<CustomerOrder> findWithItems(@Param("r") UUID restaurantId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select o from CustomerOrder o left join fetch o.items where o.id = :id and o.restaurantId = :r")
    Optional<CustomerOrder> findOne(@Param("id") UUID id, @Param("r") UUID restaurantId);
}
