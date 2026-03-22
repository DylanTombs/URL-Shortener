package com.urlshortener.repository;

import com.urlshortener.model.ShortenedUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Data access for shortened_urls table.
 *
 * Read-path (findByCode) will be routed to the read replica via DataSource
 * routing in Phase 2. The repository itself stays ignorant of replica logic.
 */
@Repository
public interface UrlRepository extends JpaRepository<ShortenedUrl, Long> {

    Optional<ShortenedUrl> findByCode(String code);

    boolean existsByCode(String code);
}
