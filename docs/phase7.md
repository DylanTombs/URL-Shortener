# Phase 7 — Final Correctness Pass

## Goal
Close the four remaining gaps identified after Phase 6. One is a correctness bug
introduced in Phase 6 that makes the concurrent-insert fix silently worse than before.
Two are design issues with real production consequences. One is docs/code drift.

---

## 7.1 Fix `@Transactional` + `DataIntegrityViolationException` catch

**Problem:** `shorten()` is annotated `@Transactional`. When `urlRepository.save()`
throws `DataIntegrityViolationException`, Spring's exception translation marks the
current transaction as **rollback-only** before the exception reaches the catch block.
The catch succeeds, but the transaction is poisoned. On the retry iteration, the next
database operation throws `UnexpectedRollbackException: Transaction silently rolled back
because it has been marked as rollback-only`. The behavior is now worse than before —
previously a clean 500, now a confusing internal Spring error that bypasses the global
exception handler entirely.

**Root cause:** You cannot catch `DataIntegrityViolationException` and continue inside
the same `@Transactional` method. Spring's AOP marks the tx rollback-only on any
unchecked exception that escapes from a `@Transactional` proxy — even if you catch it
at a lower level.

**Fix:** Remove `@Transactional` from `shorten()`. The only write operation is
`urlRepository.save()`, which carries its own `@Transactional` at the repository level.
Each save attempt gets a fresh transaction, so a constraint violation on attempt N does
not poison attempt N+1.

`existsByCode()` does not need to be in the same transaction as `save()` — it is a
hint to avoid generating a new code we already know exists, not the authoritative
uniqueness check. The UNIQUE constraint on the DB is the authoritative check.

```java
// Remove @Transactional from the method signature:
public ShortenResponse shorten(ShortenRequest request, String baseUrl) {
    ...
    for (int attempt = 1; attempt <= MAX_COLLISION_RETRIES; attempt++) {
        ...
        try {
            ShortenedUrl saved = urlRepository.save(...);
            // ← save() runs in its own repository-level transaction.
            //   If the constraint fires, the exception is catchable here.
            ...
        } catch (DataIntegrityViolationException e) {
            log.warn("code_collision_concurrent attempt={} code={}", attempt, code);
        }
    }
}
```

**Test:** The existing concurrent-collision unit test already covers this. Verify it
still passes. Add an integration test note: if Docker is available, the rate-limit
integration test exercises the full stack including the shorten path.

**Files:**
- `src/main/java/com/urlshortener/service/UrlService.java`

---

## 7.2 Change 301 to 302 and Document the Decision

**Problem:** The redirect endpoint returns `301 Moved Permanently`. Browsers cache 301s
indefinitely with no server-controlled expiry. Consequences:

1. A user who visits `sho.rt/aB3xK9mQ` and gets a 301 to `example.com/old` has that
   mapping cached in their browser permanently. If the link expires, is updated, or the
   code is reassigned (e.g. after a future TTL-based cleanup job purges old rows), that
   browser continues redirecting to the stale destination with no way to correct it.
2. `click_count` never increments for repeat visits from that browser — the redirect
   never reaches the server.
3. The cache hit rate metric becomes meaningless for users who are served by browser
   cache rather than Redis.

**Fix:** Change the response status to `302 Found`. Every redirect hits the service,
enabling accurate click counting, cache metrics, and expiry enforcement.

Add Decision 8 to `DECISIONS.md`.

```java
// UrlController.redirect():
return ResponseEntity.status(HttpStatus.FOUND)  // 302, not MOVED_PERMANENTLY
        .header(HttpHeaders.LOCATION, longUrl)
        .build();
```

**Update:** The API contract in `CLAUDE.md` documents `301` — update to `302`.

**Files:**
- `src/main/java/com/urlshortener/controller/UrlController.java`
- `DECISIONS.md`
- `CLAUDE.md`
- `src/test/java/com/urlshortener/integration/UrlControllerIT.java` (assertions check `isMovedPermanently()` — update to `isFound()`)
- `src/test/java/com/urlshortener/unit/UrlServiceTest.java` (no changes needed — service layer unaffected)

---

## 7.3 Add Path Variable Validation on `{code}`

**Problem:** `GET /{code}` accepts any string as `code` with no length or format check.
A request for `GET /aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa`
(64 chars) reaches Hibernate which issues a `SELECT ... WHERE code = ?` against the DB.
The `code` column is `VARCHAR(12)` but Postgres doesn't reject the query — it just
returns no rows, producing a 404. An attacker can probe with arbitrary strings at zero
cost, and any excessively long input is sent to the DB unnecessarily.

**Fix:** Add `@Validated` to the controller and `@Pattern` + `@Size` to the `code`
path variable:

```java
@Validated
@RestController
public class UrlController {

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(
            @PathVariable
            @Size(min = 1, max = 12, message = "code length must be between 1 and 12")
            @Pattern(regexp = "[a-zA-Z0-9]+", message = "code must be alphanumeric")
            String code) {
        ...
    }

    @GetMapping("/api/v1/urls/{code}/stats")
    public ResponseEntity<StatsResponse> stats(
            @PathVariable
            @Size(min = 1, max = 12)
            @Pattern(regexp = "[a-zA-Z0-9]+")
            String code) {
        ...
    }
}
```

`@Validated` on the class enables method-level constraint processing. Violations throw
`ConstraintViolationException` (not `MethodArgumentNotValidException`). Add a handler
for it in `GlobalExceptionHandler`:

```java
@ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
public ResponseEntity<ErrorResponse> handleConstraintViolation(
        jakarta.validation.ConstraintViolationException ex) {
    String message = ex.getConstraintViolations().stream()
            .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
            .findFirst()
            .orElse("Invalid request parameter");
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse("INVALID_CODE", message));
}
```

**Test:** Add integration tests:
- `GET /<65-char string>` → 400
- `GET /abc!def` → 400

**Files:**
- `src/main/java/com/urlshortener/controller/UrlController.java`
- `src/main/java/com/urlshortener/controller/GlobalExceptionHandler.java`
- `src/test/java/com/urlshortener/integration/UrlControllerIT.java`

---

## 7.4 Fix `show-details` Docs/Code Drift

**Problem:** Three places document that `/actuator/health` returns component-level detail:

1. `ARCHITECTURE.md` §9: "Health check: `/actuator/health` — checks both Redis and
   RDS connections"
2. `RUNBOOK.md` §Deploy to Production: `curl ... | jq -e '.status == "UP"'` (correct)
   but §Investigating High Error Rate: implies component detail is visible
3. `CLAUDE.md` API contract: `GET /actuator/health 200: { "status": "UP", "redis": "UP",
   "db": "UP" }` — this is the pre-Phase 6 response format

After Phase 6, `show-details: when_authorized` means unauthenticated callers get only
`{"status":"UP"}`. The component-level detail (`redis`, `db`) is only visible to
authenticated requests. The CD workflow health check `jq -e '.status == "UP"'` still
works — it only checks `status`.

**Fix:** Update `CLAUDE.md` API contract to remove the `redis` and `db` fields from
the health response example. Update `ARCHITECTURE.md` §9 to clarify that component
detail is available to authorized callers only.

**Files:**
- `CLAUDE.md`
- `ARCHITECTURE.md`

---

## Verification Checklist

- [ ] `mvn test` — 36+ unit tests pass, 0 failures
- [ ] `shorten()` has no `@Transactional` annotation
- [ ] Redirect returns 302, not 301 — confirmed in `UrlControllerIT`
- [ ] `GET /<65-char code>` returns 400, not 404
- [ ] `GET /abc!def` returns 400
- [ ] DECISIONS.md has Decision 8 (301 vs 302)
- [ ] CLAUDE.md health response no longer shows `redis` and `db` fields
- [ ] `mvn verify` passes in CI (Docker required for integration tests)
