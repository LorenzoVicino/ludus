package io.github.lorenzovicino.ludus.search;

/** Receives each completed iteration while the search runs. */
@FunctionalInterface
public interface SearchListener {

    SearchListener NONE = info -> {
    };

    void onIterationComplete(SearchInfo info);
}
