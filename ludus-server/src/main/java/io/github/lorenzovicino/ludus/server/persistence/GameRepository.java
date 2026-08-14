package io.github.lorenzovicino.ludus.server.persistence;

import io.github.lorenzovicino.ludus.server.domain.Game;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
