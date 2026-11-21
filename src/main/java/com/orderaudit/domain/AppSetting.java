package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One configurable setting (key/value). A NULL value means "not set". Read-only for now. */
@Entity
@Table(name = "app_setting")
public class AppSetting {

    @Id
    @Column(name = "setting_key")
    private String key;

    @Column(name = "setting_value")
    private String value;

    @Column(nullable = false)
    private String description;

    protected AppSetting() {
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public String getDescription() { return description; }
}
