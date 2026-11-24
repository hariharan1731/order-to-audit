package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One row of the customer table. */
@Entity
@Table(name = "customer")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Optional: unregistered parties have no GSTIN. */
    private String gstin;

    /** GST state code, stored explicitly (Tamil Nadu 33, Karnataka 29). Drives place of supply. */
    @Column(name = "state_code", nullable = false)
    private String stateCode;

    private String address;

    @Column(nullable = false)
    private boolean active = true;

    protected Customer() {
    }

    public Customer(String name, String gstin, String stateCode, String address) {
        this.name = name;
        this.gstin = gstin;
        this.stateCode = stateCode;
        this.address = address;
    }

    /** Changes the details. Past invoices keep their own copy of the customer or supplier details. */
    public void update(String name, String gstin, String stateCode, String address) {
        this.name = name;
        this.gstin = gstin;
        this.stateCode = stateCode;
        this.address = address;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getGstin() { return gstin; }
    public String getStateCode() { return stateCode; }
    public String getAddress() { return address; }
    public boolean isActive() { return active; }
}
