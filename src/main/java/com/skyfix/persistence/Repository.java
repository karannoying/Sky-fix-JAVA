package com.skyfix.persistence;

import com.skyfix.domain.error.PersistenceException;

import java.util.List;
import java.util.Optional;

/**
 * The common shape of every DAO (ADR-2).
 *
 * <p>Generic in the entity and its key, so the eight DAOs share one contract and the service layer
 * can be written against the interface. All SQL lives in the implementations, which is what lets
 * T-S1 assert, by scanning {@code src/main}, that no SQL is built by string concatenation
 * anywhere.
 *
 * @param <T> the entity type
 * @param <K> the key type
 */
public interface Repository<T, K> {

    /**
     * Inserts an entity and returns it with its generated key populated.
     *
     * @param entity the entity to store
     * @return the stored entity, carrying its assigned key
     * @throws PersistenceException if the insert fails
     */
    T save(T entity) throws PersistenceException;

    /**
     * Looks an entity up by key.
     *
     * @param key the key
     * @return the entity, or empty if no row has that key
     * @throws PersistenceException if the query fails
     */
    Optional<T> findById(K key) throws PersistenceException;

    /**
     * Lists every entity of this type.
     *
     * @return all rows, in a stable order defined by the implementation
     * @throws PersistenceException if the query fails
     */
    List<T> findAll() throws PersistenceException;

    /**
     * Deletes an entity by key.
     *
     * @param key the key
     * @return {@code true} if a row was deleted
     * @throws PersistenceException if the delete fails
     */
    boolean deleteById(K key) throws PersistenceException;

    /**
     * Counts the rows of this type.
     *
     * @return the row count
     * @throws PersistenceException if the query fails
     */
    long count() throws PersistenceException;
}
