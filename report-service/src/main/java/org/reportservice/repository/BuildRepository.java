package org.reportservice.repository;

import org.reportservice.model.Build;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BuildRepository extends JpaRepository<Build, UUID> {
}
