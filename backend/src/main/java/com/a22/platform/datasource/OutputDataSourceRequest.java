package com.a22.platform.datasource;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class OutputDataSourceRequest {

    @NotBlank(message = "数据源名称不能为空")
    @Size(max = 100)
    private String name;

    @NotBlank(message = "数据库类型不能为空")
    @Size(max = 40)
    private String dbType;

    @Size(max = 255)
    private String driverClass;

    @NotBlank(message = "JDBC URL 不能为空")
    @Size(max = 1000)
    private String url;

    @Size(max = 255)
    private String username;

    @Size(max = 1000)
    private String password;

    @Size(max = 40)
    private String dialect;

    @Size(max = 255)
    private String schemaName;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDbType() { return dbType; }
    public void setDbType(String dbType) { this.dbType = dbType; }
    public String getDriverClass() { return driverClass; }
    public void setDriverClass(String driverClass) { this.driverClass = driverClass; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getDialect() { return dialect; }
    public void setDialect(String dialect) { this.dialect = dialect; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
}
