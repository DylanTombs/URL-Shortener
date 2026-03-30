package com.urlshortener.repository;

import com.urlshortener.model.ShortenedUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Data access for shortened_urls table.
 *
 * Read-path (findByCode) is routed to the read replica via DataSource routing.
 * The repository itself stays ignorant of replica logic — routing is handled
 * at the DataSource level by ReadWriteRoutingDataSource.
 */
@Repository
public interface UrlRepository extends JpaRepository<ShortenedUrl, Long> {

    Optional<ShortenedUrl> findByCode(String code);

    boolean existsByCode(String code);

    @Modifying
    @Transactional
    @Query("UPDATE ShortenedUrl u SET u.clickCount = u.clickCount + 1 WHERE u.code = :code")
    void incrementClickCount(@Param("code") String code);
}
