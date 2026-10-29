package org.reportservice.model;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

/**
 * Entities whose ids the application assigns (database pattern item 9). Spring Data treats
 * an entity as new until it has been persisted or loaded, so {@code save} inserts it directly instead of merging.
 */
@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<UUID> {

    @Transient
    private boolean stored;

    @Override
    public boolean isNew() {
        return !stored;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        stored = true;
    }
}
