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
 * Read-path methods are annotated @Transactional(readOnly=true) at the repository
 * level. This makes the routing contract explicit: read-only methods route to the
 * replica regardless of the caller's transaction context, preventing reads from
 * silently hitting the primary if someone calls the repo directly.
 *
 * Write methods use @Transactional(readOnly=false) explicitly for the same reason —
 * clarity over implicit defaults.
 */
@Repository
public interface UrlRepository extends JpaRepository<ShortenedUrl, Long> {

    @Transactional(readOnly = true)
    Optional<ShortenedUrl> findByCode(String code);

    @Transactional(readOnly = true)
    boolean existsByCode(String code);

    @Modifying
    @Transactional(readOnly = false)
    @Query("UPDATE ShortenedUrl u SET u.clickCount = u.clickCount + 1 WHERE u.code = :code")
    void incrementClickCount(@Param("code") String code);
}
