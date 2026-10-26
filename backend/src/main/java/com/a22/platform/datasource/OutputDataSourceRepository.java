package com.a22.platform.datasource;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutputDataSourceRepository extends JpaRepository<OutputDataSourceEntity, Long> {
    List<OutputDataSourceEntity> findByOwnerIdOrderByNameAsc(Long ownerId);
}
