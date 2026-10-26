package com.a22.platform.datasource;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "output_data_sources",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_output_data_source_owner_name",
                columnNames = {"owner_id", "name"}),
        indexes = @Index(name = "idx_output_data_source_owner", columnList = "owner_id"))
public class OutputDataSourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "db_type", nullable = false, length = 40)
    private String dbType;

    @Column(name = "driver_class", length = 255)
    private String driverClass;

    @Column(nullable = false, length = 1000)
    private String url;

    @Column(length = 255)
    private String username;

    @Column(name = "encrypted_password", length = 2000)
    private String encryptedPassword;

    @Column(length = 40)
    private String dialect;

    @Column(name = "schema_name", length = 255)
    private String schemaName;

    @Column(name = "test_status", length = 20)
    private String testStatus;

    @Column(name = "test_message", length = 1000)
    private String testMessage;

    @Column(name = "test_at")
    private Instant testAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected OutputDataSourceEntity() { }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getDbType() { return dbType; }
    public String getDriverClass() { return driverClass; }
    public String getUrl() { return url; }
    public String getUsername() { return username; }
    public String getEncryptedPassword() { return encryptedPassword; }
    public String getDialect() { return dialect; }
    public String getSchemaName() { return schemaName; }
    public String getTestStatus() { return testStatus; }
    public String getTestMessage() { return testMessage; }
    public Instant getTestAt() { return testAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    void setName(String name) { this.name = name; }
    void setDbType(String dbType) { this.dbType = dbType; }
    void setDriverClass(String driverClass) { this.driverClass = driverClass; }
    void setUrl(String url) { this.url = url; }
    void setUsername(String username) { this.username = username; }
    void setEncryptedPassword(String encryptedPassword) { this.encryptedPassword = encryptedPassword; }
    void setDialect(String dialect) { this.dialect = dialect; }
    void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    void setTestStatus(String testStatus) { this.testStatus = testStatus; }
    void setTestMessage(String testMessage) { this.testMessage = testMessage; }
    void setTestAt(Instant testAt) { this.testAt = testAt; }
}
