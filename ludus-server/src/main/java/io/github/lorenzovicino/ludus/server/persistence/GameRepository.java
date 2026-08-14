package io.github.lorenzovicino.ludus.server.persistence;

import io.github.lorenzovicino.ludus.server.domain.Game;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Storage for games.
 *
 * <p>An interface the rest of the module depends on, with Spring Data supplying the implementation.
 * That is not ceremony here: the service layer never mentions JPA, a transaction or a session, which is
 * what keeps the multi-second search out of a database transaction — see {@code GameService}.
 */
public interface GameRepository extends JpaRepository<Game, UUID> {

    /** Most recently touched first, which is the order a "your games" list wants. */
    @Query("select g from Game g order by g.updatedAt desc")
    List<Game> findRecent(Pageable pageable);

    /**
     * Removes games nobody has touched since {@code cutoff}, returning how many went.
     *
     * <p>A bulk statement rather than loading each entity and deleting it: this runs against a table that
     * may hold tens of thousands of abandoned games, and reading them into memory to throw them away is
     * work done for nothing. The cost is that it bypasses the persistence context, which is harmless here —
     * nothing else holds these rows while the reaper runs.
     */
    @Modifying
    @Query("delete from Game g where g.updatedAt < :cutoff")
    long deleteByUpdatedAtBefore(@Param("cutoff") Instant cutoff);
}
